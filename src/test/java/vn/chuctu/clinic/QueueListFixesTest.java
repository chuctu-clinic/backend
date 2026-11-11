package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.common.Tx;
import vn.chuctu.clinic.common.VnTime;

/** Số thứ tự hàng chờ khi lọc, huỷ phiếu đặt tại quầy đã gắn tài khoản, lấp chỗ sau khi huỷ người đang khám. */
class QueueListFixesTest extends IntegrationTest {

    private int seq;

    private static LocalDate todayVn() {
        return VnTime.today(Instant.now());
    }

    /** Phiếu hôm nay cho một hồ sơ mới (hoặc hồ sơ có sẵn nếu patientId khác null), chèn thẳng database. */
    private long todayAppointment(String name, Long patientId, Long userId, String source) {
        seq++;
        Long staffId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'RECEPTIONIST'", Long.class);
        if (patientId == null) {
            jdbc.update("INSERT INTO patients (patient_code, full_name, phone, created_at, updated_at) VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                    "CT-BN-Q-" + seq, name, "09180000" + String.format("%02d", seq));
            patientId = jdbc.queryForObject("SELECT id FROM patients WHERE patient_code = ?", Long.class, "CT-BN-Q-" + seq);
        }
        LocalDate date = todayVn();
        jdbc.update("INSERT INTO appointments (ticket_code, user_id, patient_id, service_type, slot_date, slot_start, expected_time, source, "
                + "created_by, created_at, updated_at) VALUES (?, ?, ?, 'CHAM_CUU', ?, '08:00', ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "CT-PK-Q-" + seq, userId, patientId, date, Tx.utc(VnTime.slotInstant(date, "08:00")), source, staffId);
        return jdbc.queryForObject("SELECT id FROM appointments WHERE ticket_code = ?", Long.class, "CT-PK-Q-" + seq);
    }

    private long todayAppointment(String name) {
        return todayAppointment(name, null, null, "ONLINE");
    }

    @Test
    @DisplayName("[java] Lịch trong ngày lọc theo tên vẫn hiện đúng số thứ tự trong cả hàng chờ")
    void soThuTuKhiLoc() {
        var staff = client().staff();
        clinicStaff().put("/api/staff/queue/auto-invite", body("enabled", false)).expectStatus(200);
        List<Long> ids = new ArrayList<>();
        for (String name : List.of("Người Một", "Người Hai", "Người Ba")) ids.add(todayAppointment(name));
        for (long id : ids) staff.post("/api/staff/appointments/" + id + "/checkin", body()).expectStatus(200);

        String q = URLEncoder.encode("Người Ba", StandardCharsets.UTF_8);
        JsonNode items = staff.get("/api/staff/appointments?q=" + q).expectStatus(200).json().get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).at("/checkin/queueNo").intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("[java] người dùng huỷ được phiếu đặt tại quầy cho hồ sơ của mình (phiếu không ghi userId)")
    void huyPhieuQuayCuaHoSoMinh() {
        var user = client().registerUser();
        long patientId = Long.parseLong(user.selfPatientId());
        long id = todayAppointment(null, patientId, null, "COUNTER");
        user.get("/api/me/appointments/" + id).expectStatus(200);

        user.post("/api/me/appointments/" + id + "/cancel", body()).expectStatus(200);
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("[java] huỷ phiếu người đang điều trị thì tự mời người kế tiếp vào chỗ khi bật tự động mời")
    void huyNguoiDangKhamThiLapGhe() {
        var staff = client().staff();
        var clinic = clinicStaff();
        clinic.put("/api/staff/queue/auto-invite", body("enabled", true)).expectStatus(200);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) ids.add(todayAppointment("Người " + i));
        for (long id : ids) {
            staff.post("/api/staff/appointments/" + id + "/checkin", body()).expectStatus(200);
            callIn(clinic, id);
            orderTreatment(clinic, id);
        }
        var before = staff.get("/api/staff/queue").expectStatus(200).json();
        assertThat(before.get("inRoomCount").intValue()).isEqualTo(5);
        assertThat(before.get("treatmentWaitingCount").intValue()).isEqualTo(1);

        staff.post("/api/staff/appointments/" + ids.get(0) + "/cancel", body("reason", "Về sớm")).expectStatus(200);
        var after = staff.get("/api/staff/queue").expectStatus(200).json();
        assertThat(after.get("inRoomCount").intValue()).isEqualTo(5);
        assertThat(after.get("treatmentWaitingCount").intValue()).isZero();
    }
}
