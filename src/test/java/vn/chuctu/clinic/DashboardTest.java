package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.common.VnTime;

/** Cổng từ test/dashboard.test.js (tổng quan của Admin, UC.2.3). */
class DashboardTest extends IntegrationTest {

    private int seq;

    private LocalDate today() {
        return VnTime.today(clock.instant());
    }

    /** Phiếu hôm nay chèn thẳng vào database (todayAppointment của Node). */
    private long todayAppointment(String status) {
        seq++;
        Long staffId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'RECEPTIONIST'", Long.class);
        jdbc.update("INSERT INTO patients (patient_code, full_name, phone, created_at, updated_at) VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "CT-BN-D-" + seq, "Người " + seq, "09210000" + String.format("%02d", seq));
        Long patientId = jdbc.queryForObject("SELECT id FROM patients WHERE patient_code = ?", Long.class, "CT-BN-D-" + seq);
        jdbc.update("INSERT INTO appointments (ticket_code, patient_id, service_type, slot_date, slot_start, expected_time, status, source, "
                + "created_by, created_at, updated_at) VALUES (?, ?, 'CHAM_CUU', ?, '08:00', ?, ?, 'COUNTER', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "CT-PK-D-" + seq, patientId, today(), java.sql.Timestamp.from(VnTime.slotInstant(today(), "08:00")), status, staffId);
        return jdbc.queryForObject("SELECT id FROM appointments WHERE ticket_code = ?", Long.class, "CT-PK-D-" + seq);
    }

    @Test
    @DisplayName("chỉ Admin xem được")
    void chiAdminXemDuoc() {
        client().get("/api/admin/dashboard").expectStatus(401);
        client().staff().get("/api/admin/dashboard").expectStatus(403);
        client().registerUser().get("/api/admin/dashboard").expectStatus(403);
    }

    @Test
    @DisplayName("đếm phiếu trong ngày, phiếu huỷ không tính là còn hiệu lực")
    void demPhieuTrongNgay() {
        var admin = client().staff("admin@chuctu.vn");
        todayAppointment("BOOKED");
        todayAppointment("BOOKED");
        todayAppointment("CANCELLED");

        JsonNode res = admin.get("/api/admin/dashboard").expectStatus(200).json();
        assertThat(res.get("date").stringValue()).isEqualTo(today().toString());
        JsonNode appointments = res.get("appointments");
        assertThat(appointments.get("total").intValue()).isEqualTo(3);
        assertThat(appointments.get("active").intValue()).isEqualTo(2);
        assertThat(appointments.get("byStatus").size()).isEqualTo(2);
        assertThat(appointments.get("byStatus").get("BOOKED").intValue()).isEqualTo(2);
        assertThat(appointments.get("byStatus").get("CANCELLED").intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("đếm bài viết theo trạng thái và liệt kê bản nháp, không kèm nội dung")
    void demBaiViet() {
        var admin = client().staff("admin@chuctu.vn");
        JsonNode before = admin.get("/api/admin/dashboard").expectStatus(200).json();
        admin.post("/api/admin/posts", body("title", "Bản nháp mới", "category", "TIN_TUC", "content", "<p>Nháp</p>", "status", "DRAFT"))
                .expectStatus(201);

        JsonNode res = admin.get("/api/admin/dashboard").expectStatus(200).json();
        assertThat(res.get("posts").get("draft").intValue()).isEqualTo(before.get("posts").get("draft").intValue() + 1);
        assertThat(res.get("posts").get("published").intValue()).isEqualTo(before.get("posts").get("published").intValue());
        assertThat(res.get("drafts").get(0).get("title").stringValue()).isEqualTo("Bản nháp mới");
        assertThat(res.get("drafts").get(0).has("content")).isFalse();
    }

    @Test
    @DisplayName("đếm tài khoản theo vai trò và tóm tắt hàng đợi")
    void demTaiKhoanVaHangDoi() {
        var admin = client().staff("admin@chuctu.vn");
        var staff = client().staff();
        client().registerUser();
        long appt = todayAppointment("BOOKED");
        staff.post("/api/staff/appointments/" + appt + "/checkin", body()).expectStatus(200);

        JsonNode res = admin.get("/api/admin/dashboard").expectStatus(200).json();
        assertThat(res.get("accounts").get("USER").intValue()).isEqualTo(1);
        assertThat(res.get("accounts").get("ADMIN").intValue()).isEqualTo(1);
        // hồ sơ chính chủ của người dùng vừa đăng ký và hồ sơ của phiếu
        assertThat(res.get("patients").get("total").intValue()).isEqualTo(2);
        JsonNode queue = res.get("queue");
        assertThat(queue.get("inRoomCount").intValue()).isEqualTo(1);
        assertThat(queue.get("capacity").intValue()).isEqualTo(5);
        assertThat(queue.get("slotMap").isArray()).isTrue();
    }

    @Test
    @DisplayName("[java] danh sách chờ chỉ có mã phiếu và nhóm ưu tiên, bài ở mọi trạng thái được đếm")
    void javaDanhSachChoVaBaiViet() {
        var admin = client().staff("admin@chuctu.vn");
        var staff = client().staff();
        for (int i = 0; i < 6; i++) staff.post("/api/staff/appointments/" + todayAppointment("BOOKED") + "/checkin", body()).expectStatus(200);
        // Seed đã tạo 4 trang thông tin đã đăng, nên so với số trước khi thêm
        int publishedBefore = admin.get("/api/admin/dashboard").expectStatus(200).json().get("posts").get("published").intValue();
        assertThat(publishedBefore).isEqualTo(4);
        for (String status : List.of("PUBLISHED", "DRAFT", "HIDDEN")) {
            admin.post("/api/admin/posts", body("title", "Bài " + status, "summary", "Mô tả", "category", "TIN_TUC", "authorName", "BS",
                    "content", "<p>x</p>", "status", status)).expectStatus(201);
        }

        JsonNode res = admin.get("/api/admin/dashboard").expectStatus(200).json();
        JsonNode waiting = res.get("queue").get("waiting");
        assertThat(waiting.size()).isEqualTo(1);
        assertThat(waiting.get(0).size()).isEqualTo(2);
        assertThat(waiting.get(0).get("ticketCode").stringValue()).startsWith("CT-PK-D-");
        assertThat(waiting.get(0).get("priorityGroup").isString()).isTrue();
        assertThat(res.get("posts").get("published").intValue()).isEqualTo(publishedBefore + 1);
        assertThat(res.get("posts").get("draft").intValue()).isEqualTo(1);
        assertThat(res.get("posts").get("hidden").intValue()).isEqualTo(1);
    }
}
