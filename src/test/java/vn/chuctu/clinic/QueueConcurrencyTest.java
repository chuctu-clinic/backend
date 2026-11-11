package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.BookingTest.parallel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.appointment.AppointmentService;
import vn.chuctu.clinic.common.ApiException;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.queue.QueueService;
import vn.chuctu.clinic.queue.Settings;

/**
 * Mời, check-in và huỷ đồng thời trên luồng thật, không @Transactional: mỗi thao tác tự mở giao dịch của nó, như hai
 * yêu cầu HTTP khác nhau. Gọi thẳng tầng dịch vụ để các luồng xuất phát cùng lúc. Mỗi test lặp nhiều vòng trên dữ liệu
 * mới để một cuộc đua hiếm cũng lộ ra.
 */
class QueueConcurrencyTest extends IntegrationTest {

    private static final int ROUNDS = 10;

    @Autowired QueueService queue;
    @Autowired AppointmentService appointments;
    @Autowired Settings settings;

    private User staff;
    private int seq;

    @BeforeEach
    void setUp() {
        staff = users.findAll().stream().filter(u -> u.getRole().equals("RECEPTIONIST")).findFirst().orElseThrow();
        settings.set(QueueService.AUTO_KEY, false);
        seq = 0;
    }

    private record Outcome(Object value, String errorCode) {
        boolean ok() {
            return errorCode == null;
        }
    }

    private static Outcome run(java.util.function.Supplier<Object> action) {
        try {
            return new Outcome(action.get(), null);
        } catch (ApiException e) {
            return new Outcome(null, e.code());
        }
    }

    private static LocalDate today() {
        return VnTime.today(Instant.now());
    }

    /** Phiếu của một bệnh nhân mới. {@code status} BOOKED, hoặc CHECKED_IN kèm dòng check-in đang chờ. */
    private long appointment(LocalDate date, String status) {
        seq++;
        jdbc.update("INSERT INTO patients (patient_code, full_name, created_at, updated_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "CT-BN-C-" + date + "-" + seq, "Người " + seq);
        long patientId = jdbc.queryForObject("SELECT id FROM patients WHERE patient_code = ?", Long.class, "CT-BN-C-" + date + "-" + seq);
        jdbc.update("INSERT INTO appointments (ticket_code, patient_id, service_type, slot_date, slot_start, expected_time, status, source, "
                + "created_by, created_at, updated_at) VALUES (?, ?, 'CHAM_CUU', ?, '08:00', ?, ?, 'ONLINE', ?, ?, UTC_TIMESTAMP(3))",
                "CT-PK-C-" + date + "-" + seq, patientId, date, java.sql.Timestamp.from(VnTime.slotInstant(date, "08:00")), status, staff.getId(),
                java.sql.Timestamp.from(Instant.now().minusSeconds(86400)));
        return jdbc.queryForObject("SELECT id FROM appointments WHERE ticket_code = ?", Long.class, "CT-PK-C-" + date + "-" + seq);
    }

    /**
     * Một người đã được bác sĩ khám sơ bộ và chỉ định, đang chờ chỗ điều trị; trả về mã check-in. Giờ chỉ định tăng dần theo
     * thứ tự tạo.
     */
    private long waiting(LocalDate date) {
        long appt = appointment(date, "IN_PROGRESS");
        jdbc.update("INSERT INTO checkins (appointment_id, patient_id, visit_date, check_in_time, priority_group, status, treatment_kinds, "
                + "ordered_at, created_at) SELECT id, patient_id, slot_date, TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(3)), 'NORMAL', "
                + "'WAITING_TREATMENT', 'CHAM_CUU', TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(3)), UTC_TIMESTAMP(3) FROM appointments WHERE id = ?",
                seq, seq, appt);
        return jdbc.queryForObject("SELECT id FROM checkins WHERE appointment_id = ?", Long.class, appt);
    }

    /** Một người đã check-in, chờ bác sĩ gọi khám; trả về mã check-in. */
    private long waitingExam(LocalDate date) {
        long appt = appointment(date, "CHECKED_IN");
        jdbc.update("INSERT INTO checkins (appointment_id, patient_id, visit_date, check_in_time, priority_group, status, created_at) "
                + "SELECT id, patient_id, slot_date, TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(3)), 'NORMAL', 'WAITING', UTC_TIMESTAMP(3) "
                + "FROM appointments WHERE id = ?", seq, appt);
        return jdbc.queryForObject("SELECT id FROM checkins WHERE appointment_id = ?", Long.class, appt);
    }

    private List<Integer> seatsInRoom(LocalDate date) {
        return jdbc.queryForList("SELECT seat_no FROM checkins WHERE visit_date = ? AND status = 'IN_ROOM' ORDER BY seat_no", Integer.class, date);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    @DisplayName("[java] mời đồng thời không trùng chỗ điều trị: 8 người chờ, 8 luồng mời, 5 thành công, 3 báo đầy hoặc hết người")
    void moiDongThoiKhongTrungGhe() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            LocalDate date = today().plusDays(round);
            for (int i = 0; i < 8; i++) waiting(date);

            List<Outcome> results = parallel(8, i -> run(() -> queue.inviteNext(date, Instant.now(), null)));

            assertThat(results.stream().filter(Outcome::ok).count()).as("vòng " + round).isEqualTo(5);
            assertThat(results.stream().filter(r -> !r.ok()).map(Outcome::errorCode).toList()).as("vòng " + round)
                    .allMatch(c -> c.equals("ROOM_FULL") || c.equals("QUEUE_EMPTY"));
            assertThat(seatsInRoom(date)).as("vòng " + round).containsExactly(1, 2, 3, 4, 5);
            assertThat(count("SELECT COUNT(*) FROM checkins WHERE visit_date = ? AND status = 'WAITING_TREATMENT'", date)).isEqualTo(3);
            assertThat(count("SELECT COUNT(*) FROM appointments WHERE slot_date = ? AND status = 'IN_PROGRESS'", date)).isEqualTo(8);
        }
    }

    @Test
    @DisplayName("[java] mời 8 người khác nhau vào chỗ điều trị cùng lúc: 5 vào chỗ 1 đến 5 không trùng, 3 báo đầy")
    void moiTamNguoiKhacNhauCungLuc() throws Exception {
        // Mỗi luồng mời một người riêng nên các luồng cùng thấy một chỗ trống và tranh nhau: chỉ ràng buộc chỗ phân xử
        for (int round = 0; round < ROUNDS; round++) {
            LocalDate date = today().plusDays(round);
            List<Long> checkinIds = new ArrayList<>();
            for (int i = 0; i < 8; i++) checkinIds.add(waiting(date));

            List<Outcome> results = parallel(8, i -> run(() -> {
                queue.seat(checkinIds.get(i), Instant.now(), null);
                return null;
            }));

            assertThat(results.stream().filter(Outcome::ok).count()).as("vòng " + round).isEqualTo(5);
            assertThat(results.stream().filter(r -> !r.ok()).map(Outcome::errorCode).toList()).as("vòng " + round).containsOnly("ROOM_FULL");
            assertThat(seatsInRoom(date)).as("vòng " + round).containsExactly(1, 2, 3, 4, 5);
            assertThat(count("SELECT COUNT(*) FROM checkins WHERE visit_date = ? AND status = 'WAITING_TREATMENT'", date)).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("[java] lấp chỗ đồng thời từ nhiều luồng không làm trùng chỗ hay mời một người hai lần")
    void lapGheDongThoi() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            LocalDate date = today().plusDays(round);
            for (int i = 0; i < 8; i++) waiting(date);

            parallel(4, i -> {
                queue.fillSeats(date, List.of(), Instant.now());
                return null;
            });

            assertThat(seatsInRoom(date)).as("vòng " + round).containsExactly(1, 2, 3, 4, 5);
            assertThat(count("SELECT COUNT(DISTINCT appointment_id) FROM checkins WHERE visit_date = ? AND status = 'IN_ROOM'", date)).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("[java] mời cùng một người vào chỗ hai lần đồng thời: một vào chỗ, một báo không còn chờ")
    void moiCungMotNguoiHaiLan() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            LocalDate date = today().plusDays(round);
            long checkin = waiting(date);

            List<Outcome> results = parallel(2, i -> run(() -> {
                queue.seat(checkin, Instant.now(), null);
                return null;
            }));

            assertThat(results.stream().filter(Outcome::ok).count()).as("vòng " + round).isEqualTo(1);
            assertThat(results.stream().filter(r -> !r.ok()).map(Outcome::errorCode).toList()).containsExactly("NOT_WAITING");
            assertThat(seatsInRoom(date)).containsExactly(1);
        }
    }

    @Test
    @DisplayName("[java] hai bác sĩ cùng gọi người đầu hàng chờ khám: mỗi người được đúng một bác sĩ gọi")
    void haiBacSiCungGoi() throws Exception {
        User doctorA = createUser("DOCTOR");
        User doctorB = createUser("DOCTOR");
        for (int round = 0; round < ROUNDS; round++) {
            LocalDate date = today().plusDays(round);
            for (int i = 0; i < 3; i++) waitingExam(date);

            List<Outcome> results = parallel(4, i -> run(() -> queue.callNext(i % 2 == 0 ? doctorA : doctorB, date, Instant.now())));

            assertThat(results.stream().filter(Outcome::ok).count()).as("vòng " + round).isEqualTo(3);
            assertThat(results.stream().filter(r -> !r.ok()).map(Outcome::errorCode).toList()).containsExactly("QUEUE_EMPTY");
            assertThat(results.stream().filter(Outcome::ok).map(Outcome::value).distinct().count()).isEqualTo(3);
            assertThat(count("SELECT COUNT(*) FROM checkins WHERE visit_date = ? AND status = 'EXAMINING' AND doctor_id IS NOT NULL", date))
                    .isEqualTo(3);
            assertThat(count("SELECT COUNT(*) FROM appointments WHERE slot_date = ? AND status = 'IN_PROGRESS'", date)).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("[java] check-in hai lần đồng thời: một thành công, một lỗi, chỉ một check-in hiệu lực")
    void checkinHaiLanDongThoi() throws Exception {
        LocalDate date = VnTime.today(businessClock.now());
        for (int round = 0; round < ROUNDS; round++) {
            long appt = appointment(date, "BOOKED");

            List<Outcome> results = parallel(2, i -> run(() -> queue.checkIn(staff, appt)));

            assertThat(results.stream().filter(Outcome::ok).count()).as("vòng " + round).isEqualTo(1);
            assertThat(results.stream().filter(r -> !r.ok()).map(Outcome::errorCode).toList())
                    .allMatch(c -> c.equals("STALE") || c.equals("ALREADY_CHECKED_IN") || c.equals("DUPLICATE"));
            assertThat(count("SELECT COUNT(*) FROM checkins WHERE appointment_id = ? AND status IN ('WAITING', 'IN_ROOM', 'COMPLETED')", appt))
                    .as("vòng " + round).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM checkins WHERE appointment_id = ?", appt)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt)).isEqualTo("CHECKED_IN");
        }
    }

    @Test
    @DisplayName("[java] huỷ phiếu đồng thời với mời vào chỗ điều trị: không còn phiếu đã huỷ mà vẫn giữ chỗ")
    void huyDongThoiVoiMoi() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            LocalDate date = today().plusDays(round);
            long checkin = waiting(date);
            long appt = jdbc.queryForObject("SELECT appointment_id FROM checkins WHERE id = ?", Long.class, checkin);
            // Phiếu thứ hai đang chờ để lần mời nào cũng có người
            waiting(date);

            List<Outcome> results = parallel(2, i -> i == 0
                    ? run(() -> {
                        appointments.counterCancel(staff, appt, "Đặt nhầm");
                        return null;
                    })
                    : run(() -> queue.inviteNext(date, Instant.now(), null)));

            assertThat(results.get(0).errorCode()).as("vòng " + round).isIn((Object) null, "NOT_CANCELLABLE", "STALE");
            assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt)).isEqualTo("CANCELLED");
            Map<String, Object> row = jdbc.queryForMap("SELECT status, seat_no FROM checkins WHERE id = ?", checkin);
            assertThat(row.get("status")).as("vòng " + round).isEqualTo("REVERTED");
            assertThat(row.get("seat_no")).isNull();
            // Mọi chỗ đang có người đều thuộc phiếu còn hiệu lực, không chỗ nào trùng
            assertThat(count("SELECT COUNT(*) FROM checkins c JOIN appointments a ON a.id = c.appointment_id "
                    + "WHERE c.visit_date = ? AND c.status = 'IN_ROOM' AND a.status <> 'IN_PROGRESS'", date)).isZero();
            List<Integer> seats = seatsInRoom(date);
            assertThat(new HashSet<>(seats)).hasSameSizeAs(seats);
        }
    }

    @Test
    @DisplayName("[java] hoàn tác ba người cùng lúc rồi mời đồng thời giữ đúng số người trong phòng")
    void moiVaHoanTacDongThoi() throws Exception {
        LocalDate date = today();
        for (int i = 0; i < 6; i++) waiting(date);
        queue.fillSeats(date, List.of(), Instant.now());
        assertThat(seatsInRoom(date)).hasSize(5);

        // Hoàn tác ba người trong phòng cùng lúc khi tự động mời tắt: không ai mất chỗ sai, không chỗ trùng
        List<Long> inRoom = jdbc.queryForList("SELECT id FROM checkins WHERE visit_date = ? AND status = 'IN_ROOM' ORDER BY seat_no LIMIT 3", Long.class, date);
        List<Outcome> results = parallel(3, i -> run(() -> {
            queue.undoEnter(inRoom.get(i));
            return null;
        }));
        assertThat(results).allMatch(Outcome::ok);
        assertThat(seatsInRoom(date)).hasSize(2);
        List<Outcome> invites = parallel(4, i -> run(() -> queue.inviteNext(date, Instant.now(), null)));
        assertThat(invites.stream().filter(Outcome::ok).count()).isEqualTo(3);
        assertThat(seatsInRoom(date)).containsExactly(1, 2, 3, 4, 5);
    }
}
