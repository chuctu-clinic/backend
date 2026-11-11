package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static vn.chuctu.clinic.TestClient.body;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.record.MedicalRecordRepository;

/**
 * Đường khoá duy nhất của bệnh án: một người khác lưu bệnh án cho cùng phiếu ngay sau bước kiểm tra "đã có bệnh án chưa" của
 * mình. Dòng đã có sẵn trong database, còn bước kiểm tra được ép trả "chưa có" đúng một lần, rồi trở lại bình thường.
 */
class MedicalRecordRaceTest extends IntegrationTest {

    @MockitoSpyBean MedicalRecordRepository records;

    @Test
    @DisplayName("[java] bệnh án của phiếu được lưu xen vào giữa thì báo RECORD_EXISTS và giao dịch quay lui hết")
    void javaKhoaDuyNhatBaoRecordExists() {
        var staff = clinicStaff();
        var user = client().registerUser();
        long patientId = Long.parseLong(user.selfPatientId());
        Long staffId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'RECEPTIONIST'", Long.class);
        java.time.LocalDate date = VnTime.today(Instant.now());
        jdbc.update("INSERT INTO appointments (ticket_code, user_id, patient_id, service_type, slot_date, slot_start, expected_time, source, "
                + "created_by, created_at, updated_at) VALUES ('CT-PK-R-1', (SELECT user_id FROM patients WHERE id = ?), ?, 'CHAM_CUU', ?, "
                + "'08:00', ?, 'ONLINE', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", patientId, patientId, date,
                java.time.LocalDateTime.ofInstant(VnTime.slotInstant(date, "08:00"), java.time.ZoneOffset.UTC), staffId);
        long appt = jdbc.queryForObject("SELECT id FROM appointments WHERE ticket_code = 'CT-PK-R-1'", Long.class);
        staff.post("/api/staff/appointments/" + appt + "/checkin", body()).expectStatus(200);
        callIn(staff, appt);
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt)).isEqualTo("IN_PROGRESS");

        // Người kia đã lưu xong bệnh án của phiếu này
        jdbc.update("INSERT INTO medical_records (patient_id, appointment_id, visit_date, diagnosis, recorded_by, created_at, updated_at) "
                + "VALUES (?, ?, CURDATE(), 'Của người kia', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", patientId, appt, staffId);
        // Bước kiểm tra đầu của mình không thấy dòng đó; lần kiểm tra sau khi lỗi khoá thì thấy
        doReturn(false)
                .doAnswer(call -> jdbc.queryForObject("SELECT COUNT(*) FROM medical_records WHERE appointment_id = ?", Integer.class, appt) > 0)
                .when(records).existsByAppointmentId(appt);

        var res = staff.post("/api/staff/appointments/" + appt + "/record", body("diagnosis", "Của mình")).expectStatus(409).json();
        assertThat(res.get("error").get("code").stringValue()).isEqualTo("RECORD_EXISTS");
        // Giao dịch quay lui hết: phiếu vẫn đang khám, check-in vẫn đang khám sơ bộ, bệnh án của người kia còn nguyên
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT status FROM checkins WHERE appointment_id = ?", String.class, appt)).isEqualTo("EXAMINING");
        assertThat(jdbc.queryForObject("SELECT diagnosis FROM medical_records WHERE appointment_id = ?", String.class, appt))
                .isEqualTo("Của người kia");
    }
}
