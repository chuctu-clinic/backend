package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static vn.chuctu.clinic.TestClient.body;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vn.chuctu.clinic.common.Tx;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.queue.QueueService;

/** Việc lấp ghế sau khi giao dịch chính đã commit lỗi thì yêu cầu vẫn thành công, như RecordService sau khi lưu bệnh án. */
class QueuePostCommitFillTest extends IntegrationTest {

    @MockitoSpyBean QueueService queue;

    private int seq;

    private long todayAppointment() {
        seq++;
        LocalDate today = VnTime.today(Instant.now());
        Long staffId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'RECEPTIONIST'", Long.class);
        jdbc.update("INSERT INTO patients (patient_code, full_name, phone, created_at, updated_at) VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "CT-BN-F-" + seq, "Người " + seq, "09170000" + String.format("%02d", seq));
        Long patientId = jdbc.queryForObject("SELECT id FROM patients WHERE patient_code = ?", Long.class, "CT-BN-F-" + seq);
        jdbc.update("INSERT INTO appointments (ticket_code, patient_id, service_type, slot_date, slot_start, expected_time, source, "
                + "created_by, created_at, updated_at) VALUES (?, ?, 'CHAM_CUU', ?, '08:00', ?, 'ONLINE', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "CT-PK-F-" + seq, patientId, today, Tx.utc(VnTime.slotInstant(today, "08:00")), staffId);
        return jdbc.queryForObject("SELECT id FROM appointments WHERE ticket_code = ?", Long.class, "CT-PK-F-" + seq);
    }

    private void fillFails() {
        doThrow(new IllegalStateException("lỗi giả lập khi lấp ghế")).when(queue).fillSeats(any(), any(), any());
    }

    @Test
    @DisplayName("[java] check-in vẫn thành công khi lấp ghế sau commit lỗi")
    void checkInKhiLapGheLoi() {
        var staff = client().staff();
        staff.put("/api/staff/queue/auto-invite", body("enabled", true)).expectStatus(200);
        long a = todayAppointment();
        fillFails();
        staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(200);
        assertThat(jdbc.queryForObject("SELECT status FROM checkins WHERE appointment_id = ?", String.class, a)).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("[java] hoàn tác vào khám vẫn thành công khi lấp ghế sau commit lỗi")
    void undoEnterKhiLapGheLoi() {
        var staff = client().staff();
        staff.put("/api/staff/queue/auto-invite", body("enabled", false)).expectStatus(200);
        long a = todayAppointment();
        staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(200);
        staff.post("/api/staff/appointments/" + a + "/enter", body()).expectStatus(200);
        long checkinId = jdbc.queryForObject("SELECT id FROM checkins WHERE appointment_id = ?", Long.class, a);
        staff.put("/api/staff/queue/auto-invite", body("enabled", true)).expectStatus(200);
        fillFails();
        staff.post("/api/staff/queue/" + checkinId + "/undo-enter", body()).expectStatus(200);
        assertThat(jdbc.queryForObject("SELECT status FROM checkins WHERE id = ?", String.class, checkinId)).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("[java] bật tự động mời vẫn lưu cài đặt khi lấp ghế lỗi")
    void batTuDongMoiKhiLapGheLoi() {
        var staff = client().staff();
        staff.put("/api/staff/queue/auto-invite", body("enabled", false)).expectStatus(200);
        fillFails();
        var res = staff.put("/api/staff/queue/auto-invite", body("enabled", true)).expectStatus(200).json();
        assertThat(res.get("autoInvite").booleanValue()).isTrue();
    }
}
