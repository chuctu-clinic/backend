package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.common.VnTime;

/**
 * Quy trình khám có Bác sĩ và Kỹ thuật viên: tiếp tân check-in → bác sĩ gọi khám, khám sơ bộ, chỉ định → kỹ thuật viên điều trị ở
 * chỗ, lưu báo cáo (trả chỗ) → bác sĩ kết luận, lưu phiếu kết quả. Kèm phân quyền giữa các vai trò.
 */
class VisitFlowTest extends IntegrationTest {

    private User doctor;
    private User otherDoctor;
    private User technician;
    private TestClient reception;
    private TestClient doc;
    private TestClient otherDoc;
    private TestClient ktv;
    private int seq;

    @BeforeEach
    void setUp() {
        doctor = createUser("DOCTOR");
        otherDoctor = createUser("DOCTOR");
        technician = createUser("TECHNICIAN");
        reception = client().staff();
        doc = loginAs(doctor);
        otherDoc = loginAs(otherDoctor);
        ktv = loginAs(technician);
        seq = 0;
    }

    private static LocalDate today() {
        return VnTime.today(Instant.now());
    }

    /** Phiếu hôm nay (đặt online, khung 08:00) đã check-in qua API tiếp tân. Trả về mã phiếu khám. */
    private long checkedIn(String name) {
        seq++;
        Long staffId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'RECEPTIONIST'", Long.class);
        jdbc.update("INSERT INTO patients (patient_code, full_name, created_at, updated_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "CT-BN-V-" + seq, name);
        long patientId = jdbc.queryForObject("SELECT id FROM patients WHERE patient_code = ?", Long.class, "CT-BN-V-" + seq);
        jdbc.update("INSERT INTO appointments (ticket_code, patient_id, service_type, slot_date, slot_start, expected_time, source, created_by, "
                + "created_at, updated_at) VALUES (?, ?, 'CHAM_CUU', ?, '08:00', ?, 'ONLINE', ?, ?, UTC_TIMESTAMP(3))",
                "CT-PK-V-" + seq, patientId, today(), java.sql.Timestamp.from(VnTime.slotInstant(today(), "08:00")), staffId,
                java.sql.Timestamp.from(Instant.now().minusSeconds(86400)));
        long appt = jdbc.queryForObject("SELECT id FROM appointments WHERE ticket_code = ?", Long.class, "CT-PK-V-" + seq);
        businessClock.set(VnTime.slotInstant(today(), "08:05"));
        reception.post("/api/staff/appointments/" + appt + "/checkin", body()).expectStatus(200);
        return appt;
    }

    private String checkinId(long appt) {
        return String.valueOf(jdbc.queryForObject("SELECT id FROM checkins WHERE appointment_id = ? ORDER BY id DESC LIMIT 1", Long.class, appt));
    }

    private String visitStatus(long appt) {
        return jdbc.queryForObject("SELECT status FROM checkins WHERE appointment_id = ? ORDER BY id DESC LIMIT 1", String.class, appt);
    }

    private String apptStatus(long appt) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt);
    }

    private static Map<String, Object> order() {
        return body("symptoms", "Đau cổ vai gáy 2 tuần", "prelimDiagnosis", "Thoái hoá đốt sống cổ", "pulse", "78", "bloodPressure", "130/80",
                "treatmentKinds", List.of("CHAM_CUU", "XOA_BOP_BAM_HUYET"), "treatmentOrder", "Châm vùng cổ vai, xoa bóp 20 phút",
                "medicalHistory", "Cao huyết áp");
    }

    private static Map<String, Object> report() {
        return body("acupuncture", "Phong trì, Kiên tỉnh, lưu kim 20 phút", "acupressure", "Xoa bóp cổ vai 20 phút", "physio", "Chiếu đèn hồng ngoại 15 phút",
                "note", "Bệnh nhân dễ chịu");
    }

    private JsonNode record(TestClient who, long appt, int status) {
        return who.post("/api/staff/appointments/" + appt + "/record", body("diagnosis", "Thoái hoá đốt sống cổ", "pulse", "76"))
                .expectStatus(status).json();
    }

    @Test
    @DisplayName("quy trình đầy đủ: gọi khám, chỉ định, vào chỗ tự động, báo cáo trả chỗ, bác sĩ kết luận")
    void quyTrinhDayDu() {
        long appt = checkedIn("Nguyễn Văn Khám");
        assertThat(visitStatus(appt)).isEqualTo("WAITING");

        // Bác sĩ gọi người đầu hàng chờ khám
        JsonNode called = doc.post("/api/staff/exam/call-next", body()).expectStatus(200).json();
        String id = checkinId(appt);
        assertThat(called.get("calledId").stringValue()).isEqualTo(id);
        assertThat(called.get("examining").get(0).get("doctorName").stringValue()).isEqualTo(doctor.getFullName());
        assertThat(visitStatus(appt)).isEqualTo("EXAMINING");
        assertThat(apptStatus(appt)).isEqualTo("IN_PROGRESS");

        // Chưa điều trị xong thì tiếp tân không check-in lại hay hoàn tác check-in được
        reception.post("/api/staff/appointments/" + appt + "/undo-checkin", body()).expectStatus(409);

        // Chỉ định: tự động mời đang bật nên vào chỗ 1 ngay
        JsonNode state = doc.post("/api/staff/exam/" + id + "/order", order()).expectStatus(200).json();
        assertThat(visitStatus(appt)).isEqualTo("IN_ROOM");
        JsonNode seat = state.get("seats").get(0);
        assertThat(seat.get("id").stringValue()).isEqualTo(id);
        assertThat(seat.get("treatmentKinds").get(0).stringValue()).isEqualTo("CHAM_CUU");
        assertThat(seat.get("treatmentOrder").stringValue()).contains("xoa bóp");
        assertThat(seat.get("pulse").stringValue()).isEqualTo("78");
        assertThat(seat.get("medicalHistory").stringValue()).isEqualTo("Cao huyết áp");

        // Đang điều trị thì chưa kết luận được
        assertThat(record(doc, appt, 409).get("error").get("code").stringValue()).isEqualTo("NOT_READY");

        // Kỹ thuật viên lưu báo cáo: chỗ trống, chờ kết luận
        state = ktv.post("/api/staff/treatment/" + id + "/report", report()).expectStatus(200).json();
        assertThat(visitStatus(appt)).isEqualTo("WAITING_CONCLUSION");
        assertThat(state.get("inRoomCount").asInt()).isZero();
        JsonNode waitingConclusion = state.get("awaitingConclusion").get(0);
        assertThat(waitingConclusion.get("technicianName").stringValue()).isEqualTo(technician.getFullName());
        assertThat(waitingConclusion.get("report").get("acupuncture").stringValue()).contains("Phong trì");
        assertThat(waitingConclusion.get("report").get("physio").stringValue()).contains("hồng ngoại");

        // Chi tiết phiếu khám có diễn tiến lượt khám để bác sĩ đọc khi kết luận
        JsonNode detail = doc.get("/api/staff/appointments/" + appt).expectStatus(200).json();
        assertThat(detail.get("visit").get("status").stringValue()).isEqualTo("WAITING_CONCLUSION");
        assertThat(detail.get("visit").get("report").get("note").stringValue()).isEqualTo("Bệnh nhân dễ chịu");

        // Bác sĩ khác không kết luận thay được
        otherDoc.post("/api/staff/appointments/" + appt + "/record", body("diagnosis", "x")).expectStatus(403);

        // Bác sĩ đã khám kết luận: phiếu hoàn thành, tên bác sĩ lấy theo tài khoản khi không ghi
        JsonNode rec = record(doc, appt, 201).get("record");
        assertThat(rec.get("doctorName").stringValue()).isEqualTo(doctor.getFullName());
        assertThat(apptStatus(appt)).isEqualTo("COMPLETED");
        assertThat(visitStatus(appt)).isEqualTo("COMPLETED");

        // Bác sĩ sửa phiếu: lịch sử sửa ghi tên người sửa
        JsonNode edited = doc.put("/api/staff/records/" + rec.get("id").stringValue(), body("diagnosis", "Thoái hoá cột sống cổ C5-C6"))
                .expectStatus(200).json().get("record");
        assertThat(edited.get("editHistory").get(0).get("editedByName").stringValue()).isEqualTo(doctor.getFullName());
    }

    @Test
    @DisplayName("không cần điều trị: bác sĩ kết luận thẳng khi đang khám sơ bộ")
    void ketLuanThang() {
        long appt = checkedIn("Trần Thị Tư Vấn");
        doc.post("/api/staff/exam/call-next", body()).expectStatus(200);
        record(doc, appt, 201);
        assertThat(apptStatus(appt)).isEqualTo("COMPLETED");
        assertThat(visitStatus(appt)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("gọi nhầm: trả về hàng chờ khám, phiếu về Đã check-in; chưa gọi thì chưa lập phiếu được")
    void traVeHangCho() {
        long appt = checkedIn("Lê Văn Nhầm");
        record(doc, appt, 409);
        doc.post("/api/staff/exam/call-next", body()).expectStatus(200);
        String id = checkinId(appt);
        otherDoc.post("/api/staff/exam/" + id + "/undo-call", body()).expectStatus(403);
        doc.post("/api/staff/exam/" + id + "/undo-call", body()).expectStatus(200);
        assertThat(visitStatus(appt)).isEqualTo("WAITING");
        assertThat(apptStatus(appt)).isEqualTo("CHECKED_IN");
        assertThat(jdbc.queryForObject("SELECT doctor_id FROM checkins WHERE id = ?", Long.class, Long.parseLong(id))).isNull();
    }

    @Test
    @DisplayName("người đặt trước đến đúng giờ được gọi trước khách đến trực tiếp")
    void uuTienKhiGoi() {
        long walkIn = checkedIn("Khách Trực Tiếp");
        jdbc.update("UPDATE checkins SET priority_group = 'NORMAL' WHERE appointment_id = ?", walkIn);
        long booked = checkedIn("Khách Đặt Trước");
        jdbc.update("UPDATE checkins SET priority_group = 'PRIORITY' WHERE appointment_id = ?", booked);
        JsonNode called = doc.post("/api/staff/exam/call-next", body()).expectStatus(200).json();
        assertThat(called.get("calledId").stringValue()).isEqualTo(checkinId(booked));
    }

    @Test
    @DisplayName("hết chỗ thì chờ điều trị; báo cáo trả chỗ thì người kế tiếp tự vào")
    void hetGheThiCho() {
        String last = null;
        long lastAppt = 0;
        for (int i = 0; i < 6; i++) {
            long appt = checkedIn("Người " + i);
            doc.post("/api/staff/exam/call-next", body()).expectStatus(200);
            doc.post("/api/staff/exam/" + checkinId(appt) + "/order", order()).expectStatus(200);
            last = checkinId(appt);
            lastAppt = appt;
        }
        assertThat(visitStatus(lastAppt)).isEqualTo("WAITING_TREATMENT");
        String first = jdbc.queryForObject("SELECT id FROM checkins WHERE seat_no = 1 AND status = 'IN_ROOM'", Long.class).toString();
        ktv.post("/api/staff/treatment/" + first + "/report", report()).expectStatus(200);
        assertThat(visitStatus(lastAppt)).isEqualTo("IN_ROOM");
        assertThat(jdbc.queryForObject("SELECT seat_no FROM checkins WHERE id = ?", Integer.class, Long.parseLong(last))).isEqualTo(1);
    }

    @Test
    @DisplayName("sửa chỉ định khi còn chờ chỗ: về khám sơ bộ, nội dung đã ghi vẫn giữ")
    void suaChiDinh() {
        reception.get("/api/staff/queue").expectStatus(200);
        ktv.put("/api/staff/queue/auto-invite", body("enabled", false)).expectStatus(200);
        long appt = checkedIn("Phạm Sửa");
        doc.post("/api/staff/exam/call-next", body()).expectStatus(200);
        String id = checkinId(appt);
        doc.post("/api/staff/exam/" + id + "/order", order()).expectStatus(200);
        assertThat(visitStatus(appt)).isEqualTo("WAITING_TREATMENT");
        doc.post("/api/staff/exam/" + id + "/undo-order", body()).expectStatus(200);
        assertThat(visitStatus(appt)).isEqualTo("EXAMINING");
        assertThat(jdbc.queryForObject("SELECT symptoms FROM checkins WHERE id = ?", String.class, Long.parseLong(id))).isEqualTo("Đau cổ vai gáy 2 tuần");

        // Tắt tự động mời: kỹ thuật viên tự mời vào chỗ, hoàn tác thì về hàng chờ điều trị
        doc.post("/api/staff/exam/" + id + "/order", order()).expectStatus(200);
        ktv.post("/api/staff/treatment/invite", body()).expectStatus(200);
        assertThat(visitStatus(appt)).isEqualTo("IN_ROOM");
        assertThat(jdbc.queryForObject("SELECT technician_id FROM checkins WHERE id = ?", Long.class, Long.parseLong(id))).isEqualTo(technician.getId());
        ktv.post("/api/staff/treatment/" + id + "/undo-seat", body()).expectStatus(200);
        assertThat(visitStatus(appt)).isEqualTo("WAITING_TREATMENT");
        ktv.post("/api/staff/treatment/" + id + "/seat", body()).expectStatus(200);
        assertThat(visitStatus(appt)).isEqualTo("IN_ROOM");
    }

    @Test
    @DisplayName("kiểm tra dữ liệu: chỉ định phải có kỹ thuật hợp lệ, báo cáo phải ghi ít nhất một việc")
    void kiemTraDuLieu() {
        long appt = checkedIn("Võ Kiểm Tra");
        doc.post("/api/staff/exam/call-next", body()).expectStatus(200);
        String id = checkinId(appt);
        JsonNode err = doc.post("/api/staff/exam/" + id + "/order", body("treatmentKinds", List.of())).expectStatus(400).json();
        assertThat(err.get("error").get("fields").has("treatmentKinds")).isTrue();
        doc.post("/api/staff/exam/" + id + "/order", body("treatmentKinds", List.of("BAY"))).expectStatus(400);
        // Chọn "Khác" thì phải ghi rõ kỹ thuật gì
        JsonNode khac = doc.post("/api/staff/exam/" + id + "/order", body("treatmentKinds", List.of("KHAC"))).expectStatus(400).json();
        assertThat(khac.get("error").get("fields").has("treatmentOther")).isTrue();
        JsonNode state = doc.post("/api/staff/exam/" + id + "/order",
                body("treatmentKinds", List.of("CHAM_CUU", "KHAC"), "treatmentOther", "Giác hơi lưng")).expectStatus(200).json();
        assertThat(state.get("seats").get(0).get("treatmentOther").stringValue()).isEqualTo("Giác hơi lưng");
        ktv.post("/api/staff/treatment/" + id + "/report", body("note", "chỉ có ghi chú")).expectStatus(400);
        assertThat(visitStatus(appt)).isEqualTo("IN_ROOM");
    }

    @Test
    @DisplayName("phân quyền: mỗi vai trò chỉ làm việc của mình, Quản trị viên làm được tất cả")
    void phanQuyen() {
        long appt = checkedIn("Đặng Phân Quyền");
        // Tiếp tân không gọi khám, không điều trị, không lập phiếu kết quả
        reception.post("/api/staff/exam/call-next", body()).expectStatus(403);
        reception.post("/api/staff/treatment/invite", body()).expectStatus(403);
        // Kỹ thuật viên không gọi khám; bác sĩ không lưu báo cáo điều trị; cả hai không check-in
        ktv.post("/api/staff/exam/call-next", body()).expectStatus(403);
        doc.post("/api/staff/treatment/1/report", report()).expectStatus(403);
        doc.post("/api/staff/appointments/" + appt + "/undo-checkin", body()).expectStatus(403);
        ktv.get("/api/staff/appointments").expectStatus(403);
        // Ai cũng xem được hàng đợi và chi tiết phiếu khám
        for (TestClient who : List.of(reception, doc, ktv)) who.get("/api/staff/queue").expectStatus(200);
        ktv.get("/api/staff/appointments/" + appt).expectStatus(200);

        doc.post("/api/staff/exam/call-next", body()).expectStatus(200);
        reception.post("/api/staff/appointments/" + appt + "/record", body("diagnosis", "x")).expectStatus(403);
        ktv.post("/api/staff/appointments/" + appt + "/record", body("diagnosis", "x")).expectStatus(403);
        // Bác sĩ khác không chỉ định cho bệnh nhân mình không khám
        otherDoc.post("/api/staff/exam/" + checkinId(appt) + "/order", order()).expectStatus(403);

        // Quản trị viên kết luận được
        TestClient admin = client().staff("admin@chuctu.vn");
        record(admin, appt, 201);
    }
}
