package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.chuctu.clinic.common.Tx;
import vn.chuctu.clinic.common.VnTime;

/** Các chốt chặn trạng thái phiếu: người dùng không tự huỷ sau khi đã được tiếp nhận, đổi giờ không tạo hai phiếu cùng ngày. */
class AppointmentGuardsTest extends IntegrationTest {

    private int seq;

    private static LocalDate todayVn() {
        return VnTime.today(Instant.now());
    }

    /** Phiếu hôm nay của chính người dùng, chèn thẳng để không phụ thuộc giờ chạy test. */
    private long todayAppointmentOf(String patientId) {
        seq++;
        long pid = Long.parseLong(patientId);
        Long userId = jdbc.queryForObject("SELECT user_id FROM patients WHERE id = ?", Long.class, pid);
        LocalDate date = todayVn();
        jdbc.update("INSERT INTO appointments (ticket_code, user_id, patient_id, service_type, slot_date, slot_start, expected_time, source, "
                + "created_by, created_at, updated_at) VALUES (?, ?, ?, 'CHAM_CUU', ?, '08:00', ?, 'ONLINE', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "CT-PK-G-" + seq, userId, pid, date, Tx.utc(VnTime.slotInstant(date, "08:00")), userId);
        return jdbc.queryForObject("SELECT id FROM appointments WHERE ticket_code = ?", Long.class, "CT-PK-G-" + seq);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("[java] người dùng không tự huỷ được phiếu đã check-in")
    void khongHuyKhiDaCheckIn() {
        var user = client().registerUser();
        long id = todayAppointmentOf(user.selfPatientId());
        var staff = client().staff();
        staff.put("/api/staff/queue/auto-invite", body("enabled", false)).expectStatus(200);
        staff.post("/api/staff/appointments/" + id + "/checkin", body()).expectStatus(200);

        var res = user.post("/api/me/appointments/" + id + "/cancel", body()).expectStatus(409).json();
        assertThat(res.at("/error/code").asString()).isEqualTo("NOT_CANCELLABLE");
        assertThat(status(id)).isEqualTo("CHECKED_IN");
    }

    @Test
    @DisplayName("[java] người dùng không tự huỷ được phiếu đang trong phòng khám")
    void khongHuyKhiDangKham() {
        var user = client().registerUser();
        long id = todayAppointmentOf(user.selfPatientId());
        var staff = client().staff();
        staff.put("/api/staff/queue/auto-invite", body("enabled", false)).expectStatus(200);
        staff.post("/api/staff/appointments/" + id + "/checkin", body()).expectStatus(200);
        staff.post("/api/staff/queue/invite", body()).expectStatus(200);
        assertThat(status(id)).isEqualTo("IN_PROGRESS");

        user.post("/api/me/appointments/" + id + "/cancel", body()).expectStatus(409);
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT status FROM checkins WHERE appointment_id = ?", String.class, id)).isEqualTo("IN_ROOM");
    }

    @Test
    @DisplayName("[java] người dùng vẫn huỷ được phiếu chưa check-in")
    void vanHuyDuocPhieuChuaDen() {
        var user = client().registerUser();
        long id = todayAppointmentOf(user.selfPatientId());
        user.post("/api/me/appointments/" + id + "/cancel", body()).expectStatus(200);
        assertThat(status(id)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("[java] đổi giờ sang ngày bệnh nhân đã có phiếu khác bị từ chối ALREADY_BOOKED")
    void doiGioSangNgayDaCoPhieu() {
        var user = client().registerUser();
        String patientId = user.selfPatientId();
        String d2 = BookingTest.futureDate(2);
        String d3 = BookingTest.futureDate(3);
        var first = BookingTest.book(user, patientId, d2, "09:00").expectStatus(201).json().at("/appointment/id").asString();
        BookingTest.book(user, patientId, d3, "09:00").expectStatus(201);

        var res = client().staff().post("/api/staff/appointments/" + first + "/reschedule", body("slotDate", d3, "slotStart", "13:00"))
                .expectStatus(409).json();
        assertThat(res.at("/error/code").asString()).isEqualTo("ALREADY_BOOKED");
        assertThat(status(Long.parseLong(first))).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE patient_id = ? AND slot_date = ? AND status <> 'CANCELLED'",
                Integer.class, Long.parseLong(patientId), LocalDate.parse(d3))).isEqualTo(1);
    }

    @Test
    @DisplayName("[java] đặt hộ kèm check-in ngay cho ngày khác hôm nay bị từ chối WRONG_DAY, không để lại phiếu")
    void datHoCheckInNgayKhacHomNay() {
        var staff = client().staff();
        String patientId = staff.post("/api/staff/patients", body("fullName", "Khách Ngày Mai", "birthYear", 1960, "phone", "0915666001"))
                .expectStatus(201).json().at("/patient/id").asString();
        var res = staff.post("/api/staff/appointments", body("patientId", patientId, "serviceType", "CHAM_CUU",
                "slotDate", BookingTest.futureDate(1), "slotStart", "09:00", "checkInNow", true)).expectStatus(409).json();
        assertThat(res.at("/error/code").asString()).isEqualTo("WRONG_DAY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE patient_id = ?", Integer.class, Long.parseLong(patientId)))
                .isZero();
    }

    @Test
    @DisplayName("[java] đổi giờ trong cùng ngày vẫn được")
    void doiGioCungNgay() {
        var user = client().registerUser();
        String d2 = BookingTest.futureDate(2);
        var first = BookingTest.book(user, user.selfPatientId(), d2, "09:00").expectStatus(201).json().at("/appointment/id").asString();
        client().staff().post("/api/staff/appointments/" + first + "/reschedule", body("slotDate", d2, "slotStart", "13:00")).expectStatus(200);
    }
}
