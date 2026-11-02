package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;
import static vn.chuctu.clinic.BookingTest.book;
import static vn.chuctu.clinic.BookingTest.code;
import static vn.chuctu.clinic.BookingTest.futureDate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Cổng từ test/user-appointments.test.js (phiếu khám của người dùng, điều kiện đặt lịch, tra cứu ở quầy). */
class UserAppointmentsTest extends IntegrationTest {

    private static TestClient.Result bookExtra(TestClient agent, String patientId, String date, String start, Object rebookFromId) {
        return agent.post("/api/me/appointments", body("patientId", patientId, "serviceType", "CHAM_CUU", "slotDate", date,
                "slotStart", start, "rebookFromId", rebookFromId));
    }

    private static List<String> ids(TestClient agent, String query) {
        List<String> out = new ArrayList<>();
        for (JsonNode a : agent.get("/api/me/appointments" + query).expectStatus(200).json().get("items")) out.add(a.get("id").stringValue());
        return out;
    }

    private static String id(TestClient.Result res) {
        return res.json().get("appointment").get("id").stringValue();
    }

    private String rescheduledFrom(String appointmentId) {
        return jdbc.queryForObject("SELECT CAST(rescheduled_from_id AS CHAR) FROM appointments WHERE id = ?", String.class,
                Long.parseLong(appointmentId));
    }

    // ---------- Danh sách phiếu khám của người dùng (UC.6.4) ----------

    @Test
    @DisplayName("lọc theo trạng thái và chỉ có phiếu của tài khoản mình")
    void locTheoTrangThai() {
        var user = client().registerUser("0919000001", "Người Một");
        var other = client().registerUser("0919000002", "Người Hai");
        String patientId = user.selfPatientId();
        String kept = id(book(user, patientId, futureDate(2)).expectStatus(201));
        String dropped = id(book(user, patientId, futureDate(3)).expectStatus(201));
        user.post("/api/me/appointments/" + dropped + "/cancel", body()).expectStatus(200);
        book(other, other.selfPatientId(), futureDate(2)).expectStatus(201);

        assertThat(ids(user, "")).containsExactlyInAnyOrder(kept, dropped);
        assertThat(ids(user, "?status=BOOKED")).containsExactly(kept);
        assertThat(ids(user, "?status=CANCELLED")).containsExactly(dropped);
        assertThat(ids(user, "?status=COMPLETED")).isEmpty();
    }

    @Test
    @Disabled("Task 9")
    @DisplayName("đánh dấu phiếu đã có kết quả khám")
    void danhDauPhieuCoKetQua() {
        // Node: tiếp tân check-in (Task 8) rồi lưu bệnh án (Task 9). Bản [java] bên dưới chèn bệnh án thẳng vào database.
    }

    @Test
    @DisplayName("[java] đánh dấu phiếu đã có kết quả khám (bệnh án chèn thẳng vào database)")
    void javaDanhDauPhieuCoKetQua() {
        var user = client().registerUser();
        String patientId = user.selfPatientId();
        String apptId = id(book(user, patientId, futureDate(2)).expectStatus(201));
        assertThat(hasResult(user)).isFalse();
        assertThat(user.get("/api/me/appointments/" + apptId).expectStatus(200).json().get("appointment").get("hasResult").booleanValue())
                .isFalse();

        Long staffId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'RECEPTIONIST'", Long.class);
        jdbc.update("INSERT INTO medical_records (patient_id, appointment_id, visit_date, diagnosis, recorded_by, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'Đau vai', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", Long.parseLong(patientId), Long.parseLong(apptId),
                futureDate(2), staffId);
        assertThat(hasResult(user)).isTrue();
        assertThat(user.get("/api/me/appointments/" + apptId).expectStatus(200).json().get("appointment").get("hasResult").booleanValue())
                .isTrue();
    }

    private static boolean hasResult(TestClient user) {
        return user.get("/api/me/appointments").expectStatus(200).json().get("items").get(0).get("hasResult").booleanValue();
    }

    // ---------- Điều kiện đặt lịch (UC.6.3) ----------

    @Test
    @DisplayName("một người chỉ có một phiếu trong ngày")
    void motPhieuMotNgay() {
        var user = client().registerUser();
        String patientId = user.selfPatientId();
        String date = futureDate(2);
        book(user, patientId, date).expectStatus(201);
        var res = book(user, patientId, date, "10:00").expectStatus(409);
        assertThat(code(res)).isEqualTo("ALREADY_BOOKED");
    }

    @Test
    @DisplayName("hồ sơ chưa khai quan hệ thì chưa đặt lịch được")
    void chuaKhaiQuanHe() {
        var user = client().registerUser();
        String patientId = user.selfPatientId();
        jdbc.update("UPDATE patients SET relationship = NULL WHERE id = ?", Long.parseLong(patientId));
        var res = book(user, patientId, futureDate(2)).expectStatus(400);
        assertThat(res.json().get("error").get("fields").has("relationship")).isTrue();
    }

    @Test
    @DisplayName("đặt lại từ phiếu đã huỷ thì ghi nhớ phiếu cũ")
    void datLaiGhiNhoPhieuCu() {
        var user = client().registerUser();
        String patientId = user.selfPatientId();
        String old = id(book(user, patientId, futureDate(2)).expectStatus(201));
        user.post("/api/me/appointments/" + old + "/cancel", body()).expectStatus(200);

        String next = id(bookExtra(user, patientId, futureDate(3), "09:00", old).expectStatus(201));
        assertThat(rescheduledFrom(next)).isEqualTo(old);
    }

    @Test
    @DisplayName("không ghi nhớ phiếu cũ nếu phiếu đó chưa huỷ hoặc của tài khoản khác")
    void khongGhiNhoPhieuCuKhongHopLe() {
        var owner = client().registerUser("0919000011", "Chủ Phiếu");
        var other = client().registerUser("0919000012", "Người Khác");
        String ownerPatient = owner.selfPatientId();
        String active = id(book(owner, ownerPatient, futureDate(2)).expectStatus(201));
        String cancelled = id(book(owner, ownerPatient, futureDate(3)).expectStatus(201));
        owner.post("/api/me/appointments/" + cancelled + "/cancel", body()).expectStatus(200);

        String a = id(bookExtra(other, other.selfPatientId(), futureDate(4), "09:00", cancelled).expectStatus(201));
        assertThat(rescheduledFrom(a)).isNull();
        String b = id(bookExtra(owner, ownerPatient, futureDate(5), "09:00", active).expectStatus(201));
        assertThat(rescheduledFrom(b)).isNull();
    }

    // ---------- Tra cứu phiếu ở Lịch trong ngày (UC.7.1, UC.7.2) ----------

    private static List<String> search(TestClient staff, String date, String q) {
        List<String> out = new ArrayList<>();
        String path = "/api/staff/appointments?date=" + date + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
        for (JsonNode a : staff.get(path).expectStatus(200).json().get("items")) out.add(a.get("id").stringValue());
        return out;
    }

    @Test
    @DisplayName("tìm theo mã phiếu, tên, số điện thoại trong ngày đang xem")
    void timTheoMaTenSoDienThoai() {
        var staff = client().staff();
        var a = client().registerUser("0919000021", "Phạm Văn Tìm");
        var b = client().registerUser("0919000022", "Đỗ Thị Khác");
        String date = futureDate(2);
        var ticket = book(a, a.selfPatientId(), date).expectStatus(201).json().get("appointment");
        book(b, b.selfPatientId(), date).expectStatus(201);

        String ticketId = ticket.get("id").stringValue();
        assertThat(search(staff, date, "Phạm Văn")).containsExactly(ticketId);
        assertThat(search(staff, date, "0919000021")).containsExactly(ticketId);
        assertThat(search(staff, date, ticket.get("ticketCode").stringValue())).containsExactly(ticketId);
        assertThat(search(staff, date, "Không có ai")).isEmpty();
        // Ký tự đặc biệt trong ô tìm kiếm không được làm hỏng truy vấn
        assertThat(search(staff, date, "([")).isEmpty();
    }

    @Test
    @DisplayName("tra mã phiếu ở quầy, mã không có thì trả về rỗng, mã quá ngắn thì báo lỗi")
    void traMaPhieuOQuay() {
        var staff = client().staff();
        var user = client().registerUser();
        var ticket = book(user, user.selfPatientId(), futureDate(2)).expectStatus(201).json().get("appointment");
        var found = staff.get("/api/staff/appointments/lookup?code=" + ticket.get("ticketCode").stringValue()).expectStatus(200).json();
        assertThat(found.get("appointment").get("id").stringValue()).isEqualTo(ticket.get("id").stringValue());
        assertThat(staff.get("/api/staff/appointments/lookup?code=CT-PK-00-0000-000").expectStatus(200).json().get("appointment").isNull())
                .isTrue();
        staff.get("/api/staff/appointments/lookup?code=x").expectStatus(400);
    }

    @Test
    @DisplayName("khung giờ ở quầy dùng quy tắc đặt hộ")
    void khungGioOQuay() {
        var staff = client().staff();
        for (JsonNode s : staff.get("/api/staff/availability?date=" + futureDate(2)).expectStatus(200).json().get("slots")) {
            assertThat(s.get("available").booleanValue()).isTrue();
        }
        for (JsonNode s : staff.get("/api/staff/availability?date=" + futureDate(8)).expectStatus(200).json().get("slots")) {
            assertThat(s.get("available").booleanValue()).isFalse();
        }
    }

    // ---------- Thêm bằng Java ----------

    @Test
    @DisplayName("[java] phiếu của người dùng không có số điện thoại, phiếu quầy có; mã quá lớn hay sai dạng là 404")
    void javaHinhDangPhieu() {
        var user = client().registerUser();
        var staff = client().staff();
        String apptId = id(book(user, user.selfPatientId(), futureDate(2)).expectStatus(201));
        var mine = user.get("/api/me/appointments/" + apptId).expectStatus(200).json().get("appointment");
        assertThat(mine.get("serviceLabel").stringValue()).isEqualTo("Châm cứu");
        assertThat(mine.get("expectedTime").stringValue()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(mine.get("patient").has("phone")).isTrue(); // bản đầy đủ có số điện thoại
        var listed = user.get("/api/me/appointments").expectStatus(200).json().get("items").get(0);
        assertThat(listed.get("patient").has("phone")).isFalse();
        assertThat(listed.get("patient").has("dob")).isFalse();
        var counter = staff.get("/api/staff/appointments/" + apptId).expectStatus(200).json();
        assertThat(counter.get("appointment").get("patient").has("phone")).isTrue();
        assertThat(counter.get("record").isNull()).isTrue();

        user.get("/api/me/appointments/99999999999999999999").expectStatus(404);
        user.get("/api/me/appointments/khong-phai-id").expectStatus(404);
        staff.get("/api/staff/appointments/99999999999999999999").expectStatus(404);
    }

    @Test
    @DisplayName("[java] dữ liệu đặt lịch sai báo lỗi từng ô")
    void javaLoiTungO() {
        var user = client().registerUser();
        var res = user.post("/api/me/appointments", body("patientId", "abc", "serviceType", "SAI", "slotDate", "2026-02-30",
                "slotStart", "12:00")).expectStatus(400);
        var fields = res.json().get("error").get("fields");
        assertThat(code(res)).isEqualTo("VALIDATION");
        assertThat(fields.get("patientId").stringValue()).isEqualTo("Mã không hợp lệ.");
        assertThat(fields.get("serviceType").stringValue()).isEqualTo("Cô chú vui lòng chọn nội dung khám.");
        assertThat(fields.get("slotDate").stringValue()).isEqualTo("Ngày không hợp lệ.");
        assertThat(fields.get("slotStart").stringValue()).isEqualTo("Cô chú vui lòng chọn giờ khám.");
        var empty = user.post("/api/me/appointments", body()).expectStatus(400);
        assertThat(empty.json().get("error").get("fields").get("patientId").stringValue()).isEqualTo("Required");
    }

    @Test
    @DisplayName("[java] huỷ hai lần và huỷ phiếu đã hoàn thành báo NOT_CANCELLABLE")
    void javaHuyKhongHopLe() {
        var user = client().registerUser();
        String patientId = user.selfPatientId();
        String apptId = id(book(user, patientId, futureDate(2)).expectStatus(201));
        String path = "/api/me/appointments/" + apptId + "/cancel";
        var cancelled = user.post(path, body("reason", "Bận")).expectStatus(200).json().get("appointment");
        assertThat(cancelled.get("cancelReason").stringValue()).isEqualTo("Bận");
        assertThat(cancelled.get("cancelledAt").stringValue()).endsWith("Z");
        var again = user.post(path, body()).expectStatus(409);
        assertThat(code(again)).isEqualTo("NOT_CANCELLABLE");
        assertThat(again.json().get("error").get("message").stringValue()).isEqualTo("Phiếu khám này đã được huỷ trước đó.");

        String done = id(book(user, patientId, futureDate(3)).expectStatus(201));
        jdbc.update("UPDATE appointments SET status = 'COMPLETED' WHERE id = ?", Long.parseLong(done));
        var res = user.post("/api/me/appointments/" + done + "/cancel", body()).expectStatus(409);
        assertThat(res.json().get("error").get("message").stringValue()).isEqualTo("Phiếu khám đã hoàn thành nên không huỷ được.");
        assertThat(user.get("/api/me/appointments/" + done).expectStatus(200).json().get("appointment").get("status").stringValue())
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("[java] lịch trong ngày của tiếp tân có tổng theo trạng thái; danh sách ngày có phiếu trong tháng bỏ phiếu huỷ")
    void javaLichTrongNgay() {
        var staff = client().staff();
        var a = client().registerUser("0919000031", "Người Một");
        var b = client().registerUser("0919000032", "Người Hai");
        String date = futureDate(2);
        book(a, a.selfPatientId(), date, "08:00").expectStatus(201);
        String second = id(book(b, b.selfPatientId(), date, "13:00").expectStatus(201));
        String cancelledDate = futureDate(4);
        String third = id(book(b, b.selfPatientId(), cancelledDate, "13:00").expectStatus(201));
        b.post("/api/me/appointments/" + third + "/cancel", body()).expectStatus(200);
        b.post("/api/me/appointments/" + second + "/cancel", body()).expectStatus(200);

        var day = staff.get("/api/staff/appointments?date=" + date).expectStatus(200).json();
        assertThat(day.get("date").stringValue()).isEqualTo(date);
        assertThat(day.get("items").size()).isEqualTo(2);
        assertThat(day.get("items").get(0).get("slotStart").stringValue()).isEqualTo("08:00");
        assertThat(day.get("summary").get("BOOKED").intValue()).isEqualTo(1);
        assertThat(day.get("summary").get("CANCELLED").intValue()).isEqualTo(1);
        assertThat(day.get("summary").get("COMPLETED").intValue()).isEqualTo(0);
        assertThat(staff.get("/api/staff/appointments?date=" + date + "&status=CANCELLED").expectStatus(200).json().get("items").size())
                .isEqualTo(1);
        staff.get("/api/staff/appointments?date=2026-02-30").expectStatus(400);

        String month = date.substring(0, 7);
        var dates = staff.get("/api/staff/appointments/dates?month=" + month).expectStatus(200).json();
        List<String> list = new ArrayList<>();
        for (JsonNode d : dates.get("dates")) list.add(d.stringValue());
        assertThat(list).contains(date).doesNotContain(cancelledDate);
        staff.get("/api/staff/appointments/dates?month=2026-1").expectStatus(400);
    }

    @Test
    @DisplayName("[java] hồ sơ bệnh nhân của tiếp tân liệt kê phiếu khám")
    void javaHoSoLietKePhieu() {
        var user = client().registerUser();
        var staff = client().staff();
        String patientId = user.selfPatientId();
        String first = id(book(user, patientId, futureDate(2)).expectStatus(201));
        String second = id(book(user, patientId, futureDate(3)).expectStatus(201));
        var res = staff.get("/api/staff/patients/" + patientId).expectStatus(200).json();
        assertThat(res.get("appointments").size()).isEqualTo(2);
        // Mới nhất trước
        assertThat(res.get("appointments").get(0).get("id").stringValue()).isEqualTo(second);
        assertThat(res.get("appointments").get(1).get("id").stringValue()).isEqualTo(first);
        assertThat(res.get("appointments").get(0).get("patient").get("id").stringValue()).isEqualTo(patientId);
    }
}
