package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.BookingTest.futureDate;
import static vn.chuctu.clinic.BookingTest.parallel;
import static vn.chuctu.clinic.TestClient.body;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.appointment.Appointment;
import vn.chuctu.clinic.appointment.AppointmentService;
import vn.chuctu.clinic.appointment.AppointmentService.BookingRequest;
import vn.chuctu.clinic.common.ApiException;
import vn.chuctu.clinic.common.VnTime;

/**
 * Đặt, huỷ và tự huỷ đồng thời trên luồng thật, không @Transactional: mỗi thao tác tự mở giao dịch của nó, nên chạy
 * như hai yêu cầu HTTP khác nhau. Gọi thẳng tầng dịch vụ để cho các luồng xuất phát cùng lúc.
 */
class BookingConcurrencyTest extends IntegrationTest {

    @Autowired AppointmentService appointments;

    private User staff;
    private TestClient staffClient;
    private int phoneSeq;

    @BeforeEach
    void setUpStaff() {
        staffClient = client().staff();
        staff = users.findAll().stream().filter(u -> u.getRole().equals("RECEPTIONIST")).findFirst().orElseThrow();
        phoneSeq = 0;
    }

    private long newPatient() {
        String phone = "0916" + String.format("%06d", ++phoneSeq);
        return Long.parseLong(staffClient.post("/api/staff/patients", body("fullName", "Bệnh nhân " + phoneSeq, "birthYear", 1960,
                "phone", phone)).expectStatus(201).json().get("patient").get("id").stringValue());
    }

    private List<Long> newPatients(int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(newPatient());
        return ids;
    }

    private BookingRequest request(long patientId, LocalDate date, String start) {
        return new BookingRequest(patientId, "CHAM_CUU", date, start, null);
    }

    /** Kết quả một lần gọi: phiếu, hoặc mã lỗi nghiệp vụ. */
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

    private int slotCount(LocalDate date, String start) {
        List<Integer> rows = jdbc.query("SELECT `count` FROM slot_counters WHERE slot_date = ? AND start_time = ?",
                (rs, i) -> rs.getInt(1), date, start);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private int countAppointments(String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE " + where, Integer.class);
    }

    private String status(long appointmentId) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    @Test
    @DisplayName("[java] chín người đặt sau cùng một khung còn 6 chỗ: đúng 6 thành công, 3 SLOT_FULL")
    void chinNguoiDatSauCho() throws Exception {
        LocalDate date = LocalDate.parse(futureDate(3));
        List<Long> patients = newPatients(9);
        List<Outcome> results = parallel(9, i -> run(() -> appointments.counterBook(staff, request(patients.get(i), date, "10:00"))));

        assertThat(results.stream().filter(Outcome::ok).count()).isEqualTo(6);
        assertThat(results.stream().filter(r -> "SLOT_FULL".equals(r.errorCode())).count()).isEqualTo(3);
        assertThat(slotCount(date, "10:00")).isEqualTo(6);
        assertThat(countAppointments("status = 'BOOKED'")).isEqualTo(6);
        assertThat(countAppointments("slot_date = '" + date + "' AND slot_start = '10:00'")).isEqualTo(6);
    }

    @Test
    @DisplayName("[java] mã phiếu không trùng khi đặt đồng thời")
    void maPhieuKhongTrungKhiDatDongThoi() throws Exception {
        LocalDate date = LocalDate.parse(futureDate(2));
        List<String> starts = List.of("08:00", "09:00", "10:00", "11:00", "13:00", "14:00", "15:00", "16:00");
        List<Long> patients = newPatients(8);
        List<Outcome> results = parallel(8, i -> run(() -> appointments.counterBook(staff, request(patients.get(i), date, starts.get(i)))));

        assertThat(results).allMatch(Outcome::ok);
        List<String> codes = jdbc.queryForList("SELECT ticket_code FROM appointments", String.class);
        assertThat(codes).hasSize(8);
        assertThat(new HashSet<>(codes)).hasSize(8);
        String prefix = "CT-PK-" + VnTime.yy(date) + "-" + VnTime.ddmm(date) + "-";
        for (int seq = 1; seq <= 8; seq++) assertThat(codes).contains(prefix + String.format("%03d", seq));
    }

    @Test
    @DisplayName("[java] huỷ trùng với tự huỷ nhả chỗ đúng một lần")
    void huyTrungVoiTuHuyNhaChoMotLan() throws Exception {
        // Đồng hồ khung giờ cố định 10:00 hôm nay để đặt được khung 15:00 hôm nay; đồng hồ tự huỷ chạy sau 17:00
        Instant morning = TestClocks.defaultBusinessNow();
        clock.set(morning);
        LocalDate today = VnTime.today(morning);
        Instant evening = VnTime.slotInstant(today, "17:30");
        long patient = newPatient();
        // Phiếu thứ hai cùng khung đã check-in nên tự huỷ bỏ qua: nhả chỗ hai lần thì count tụt từ 2 xuống 0,
        // không còn bị sàn count > 0 che như khi khung chỉ có một phiếu
        Appointment checkedIn = appointments.counterBook(staff, request(newPatient(), today, "15:00"));
        jdbc.update("UPDATE appointments SET status = 'CHECKED_IN' WHERE id = ?", checkedIn.getId());

        for (int round = 0; round < 15; round++) {
            Appointment appt = appointments.counterBook(staff, request(patient, today, "15:00"));
            assertThat(slotCount(today, "15:00")).isEqualTo(2);
            // Một huỷ tay và ba lần tự huỷ chồng nhau (như hai lượt job gối nhau): phiếu chỉ được nhả chỗ một lần
            List<Outcome> results = parallel(4, i -> i == 0
                    ? run(() -> { appointments.counterCancel(staff, appt.getId(), "Đặt nhầm"); return null; })
                    : run(() -> appointments.autoCancelNoShows(evening)));

            assertThat(status(appt.getId())).as("vòng " + round).isEqualTo("CANCELLED");
            assertThat(slotCount(today, "15:00")).as("vòng " + round).isEqualTo(1);
            assertThat(status(checkedIn.getId())).isEqualTo("CHECKED_IN");
            // Lỗi duy nhất chấp nhận được là phiếu đã bị bên kia huỷ trước
            for (Outcome r : results) assertThat(r.errorCode()).isIn((Object) null, "NOT_CANCELLABLE", "STALE");
        }
    }

    @Test
    @DisplayName("[java] huỷ hai lần đồng thời nhả chỗ đúng một lần")
    void huyHaiLanDongThoiNhaChoMotLan() throws Exception {
        LocalDate date = LocalDate.parse(futureDate(2));
        long p1 = newPatient();
        long p2 = newPatient();
        Appointment first = appointments.counterBook(staff, request(p1, date, "09:00"));
        appointments.counterBook(staff, request(p2, date, "09:00"));
        assertThat(slotCount(date, "09:00")).isEqualTo(2);

        List<Outcome> results = parallel(2, i -> run(() -> { appointments.counterCancel(staff, first.getId(), "Đặt nhầm"); return null; }));

        assertThat(results.stream().filter(Outcome::ok).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.ok()).map(Outcome::errorCode).toList()).allMatch(c -> c.equals("NOT_CANCELLABLE") || c.equals("STALE"));
        assertThat(slotCount(date, "09:00")).isEqualTo(1);
        assertThat(status(first.getId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("[java] đổi giờ sang khung đầy giữ nguyên phiếu cũ")
    void doiGioKhungDayGiuNguyenPhieuCu() {
        LocalDate date = LocalDate.parse(futureDate(2));
        LocalDate newDate = LocalDate.parse(futureDate(3));
        long mover = newPatient();
        Appointment old = appointments.counterBook(staff, request(mover, date, "08:00"));
        for (Long other : newPatients(6)) appointments.counterBook(staff, request(other, newDate, "13:00"));
        assertThat(slotCount(newDate, "13:00")).isEqualTo(6);

        ApiException error = null;
        try {
            appointments.reschedule(staff, old.getId(), newDate, "13:00");
        } catch (ApiException e) {
            error = e;
        }
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("SLOT_FULL");
        assertThat(status(old.getId())).isEqualTo("BOOKED");
        assertThat(slotCount(date, "08:00")).isEqualTo(1);
        assertThat(slotCount(newDate, "13:00")).isEqualTo(6);
        assertThat(countAppointments("rescheduled_from_id IS NOT NULL")).isEqualTo(0);
        assertThat(countAppointments("slot_date = '" + newDate + "'")).isEqualTo(6);
    }

    @Test
    @DisplayName("[java] hai phiếu cùng đổi sang chỗ cuối của một khung: một thắng, một SLOT_FULL, không phiếu nào mất chỗ")
    void haiPhieuCungDoiGioVaoChoCuoi() throws Exception {
        LocalDate date = LocalDate.parse(futureDate(2));
        LocalDate newDate = LocalDate.parse(futureDate(3));
        Appointment a = appointments.counterBook(staff, request(newPatient(), date, "08:00"));
        Appointment b = appointments.counterBook(staff, request(newPatient(), date, "09:00"));
        for (Long other : newPatients(5)) appointments.counterBook(staff, request(other, newDate, "13:00"));

        List<Outcome> results = parallel(2, i -> run(() -> appointments.reschedule(staff, i == 0 ? a.getId() : b.getId(), newDate, "13:00")));

        assertThat(results.stream().filter(Outcome::ok).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> "SLOT_FULL".equals(r.errorCode())).count()).isEqualTo(1);
        assertThat(slotCount(newDate, "13:00")).isEqualTo(6);
        // Phiếu thắng đã nhả khung cũ, phiếu thua vẫn giữ khung cũ
        assertThat(slotCount(date, "08:00") + slotCount(date, "09:00")).isEqualTo(1);
        assertThat(countAppointments("status = 'BOOKED' AND slot_date = '" + date + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("[java] hai luồng cùng đổi giờ một phiếu: một thắng, một lỗi nghiệp vụ, chỗ đếm đúng")
    void haiLuongCungDoiGioMotPhieu() throws Exception {
        LocalDate date = LocalDate.parse(futureDate(2));
        LocalDate newDate = LocalDate.parse(futureDate(3));
        Appointment old = appointments.counterBook(staff, request(newPatient(), date, "08:00"));

        List<Outcome> results = parallel(2, i -> run(() -> appointments.reschedule(staff, old.getId(), newDate, i == 0 ? "13:00" : "14:00")));

        assertThat(results.stream().filter(Outcome::ok).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.ok()).map(Outcome::errorCode).toList())
                .allMatch(c -> c.equals("NOT_RESCHEDULABLE") || c.equals("STALE"));
        assertThat(status(old.getId())).isEqualTo("CANCELLED");
        assertThat(slotCount(date, "08:00")).isEqualTo(0);
        assertThat(slotCount(newDate, "13:00") + slotCount(newDate, "14:00")).isEqualTo(1);
        assertThat(countAppointments("status = 'BOOKED'")).isEqualTo(1);
        assertThat(countAppointments("rescheduled_from_id = " + old.getId())).isEqualTo(1);
    }
}
