package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.patient.Patient;
import vn.chuctu.clinic.patient.PatientRepository;
import vn.chuctu.clinic.patient.PatientService;

/** Cổng từ test/profiles.test.js, thêm các test [java] cho những chỗ test Node cần đặt lịch hoặc tài khoản Admin. */
class ProfilesTest extends IntegrationTest {

    @Autowired PatientRepository patients;
    @Autowired PatientService patientService;

    private static final Map<String, Object> RELATIVE =
            body("fullName", "Lê Thị Mẹ", "birthYear", 1945, "gender", "NU", "relationship", "KHAC");

    private JsonNode addRelative(TestClient agent) {
        return addRelative(agent, RELATIVE);
    }

    private JsonNode addRelative(TestClient agent, Map<String, Object> data) {
        return agent.post("/api/me/patients", data).expectStatus(201).json().get("patient");
    }

    private static String futureDate(int days) {
        return LocalDate.now(ZoneOffset.ofHours(7)).plusDays(days).toString();
    }

    private TestClient.Result book(TestClient agent, String patientId) {
        return book(agent, patientId, futureDate(2));
    }

    private TestClient.Result book(TestClient agent, String patientId, String slotDate) {
        return agent.post("/api/me/appointments",
                body("patientId", patientId, "serviceType", "CHAM_CUU", "slotDate", slotDate, "slotStart", "09:00"));
    }

    private static JsonNode account(TestClient agent) {
        return agent.get("/api/me").expectStatus(200).json().get("user");
    }

    private Patient patient(String id) {
        return patients.findById(Long.parseLong(id)).orElseThrow();
    }

    private static JsonNode fields(TestClient.Result res) {
        return res.json().get("error").get("fields");
    }

    private static String errorCode(TestClient.Result res) {
        return res.json().get("error").get("code").stringValue();
    }

    /** Phiếu khám chèn thẳng vào database (đặt lịch thuộc Task 7). */
    private long insertAppointment(long patientId, long userId, String slotDate, String status) {
        jdbc.update("INSERT INTO appointments (ticket_code, user_id, patient_id, service_type, slot_date, slot_start, "
                + "expected_time, status, source, created_by, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'CHAM_CUU', ?, '09:00', UTC_TIMESTAMP(3), ?, 'ONLINE', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "CT-PK-26-" + System.nanoTime() % 100000000L, userId, patientId, slotDate, status, userId);
        return jdbc.queryForObject("SELECT MAX(id) FROM appointments", Long.class);
    }

    // ---------- Thông tin tài khoản (UC.1.4, UC.1.5) ----------

    @Test
    @DisplayName("không tự đổi được số điện thoại đăng nhập (BR.01)")
    void khongTuDoiSoDienThoai() {
        var user = client().registerUser("0918000001", "Nguyễn Văn A");
        var res = user.patch("/api/me", body("phone", "0918000002")).expectStatus(400);
        assertThat(res.json().get("error").get("message").stringValue()).contains("không tự sửa được");
        assertThat(account(user).get("phone").stringValue()).isEqualTo("0918000001");
        user.patch("/api/me", body("phone", "0918000001", "fullName", "Tên Mới")).expectStatus(200);
    }

    @Test
    @DisplayName("sửa tài khoản thì hồ sơ chính chủ cập nhật theo")
    void suaTaiKhoanThiHoSoCapNhat() {
        var user = client().registerUser();
        user.patch("/api/me", body("fullName", "Tên Mới", "dob", "1970-05-06", "gender", "NU", "address", "Quận 1")).expectStatus(200);
        Patient self = patient(user.selfPatientId());
        assertThat(self.getFullName()).isEqualTo("Tên Mới");
        assertThat(self.getGender()).isEqualTo("NU");
        assertThat(self.getAddress()).isEqualTo("Quận 1");
        assertThat(self.getBirthYear()).isEqualTo(1970);
    }

    @Test
    @DisplayName("không xoá trắng được ngày sinh, địa chỉ")
    void khongXoaTrangNgaySinhDiaChi() {
        var user = client().registerUser();
        var res = user.patch("/api/me", body("dob", "", "address", "  ")).expectStatus(400);
        assertThat(fields(res).has("dob")).isTrue();
        assertThat(fields(res).has("address")).isTrue();
    }

    // ---------- Hồ sơ chính chủ luôn khớp với tài khoản ----------

    private static final Map<String, Object> PROFILE_EDIT =
            body("fullName", "Tên Hồ Sơ", "dob", "1960-02-03", "gender", "NU", "address", "Quận 3");

    @Test
    @DisplayName("sửa hồ sơ chính chủ thì tài khoản cập nhật theo và trả về tài khoản mới")
    void suaHoSoChinhChuThiTaiKhoanCapNhat() {
        var user = client().registerUser();
        var res = user.patch("/api/me/patients/" + user.selfPatientId(), PROFILE_EDIT).expectStatus(200).json();
        assertThat(res.get("user").get("fullName").stringValue()).isEqualTo("Tên Hồ Sơ");
        var me = account(user);
        assertThat(me.get("fullName").stringValue()).isEqualTo("Tên Hồ Sơ");
        assertThat(me.get("gender").stringValue()).isEqualTo("NU");
        assertThat(me.get("address").stringValue()).isEqualTo("Quận 3");
        assertThat(me.get("dob").stringValue()).startsWith("1960-02-03");
    }

    @Test
    @DisplayName("sửa hồ sơ người thân không đụng tới tài khoản")
    void suaHoSoNguoiThanKhongDungTaiKhoan() {
        var user = client().registerUser("0918000021", "Chủ Tài Khoản");
        var relative = addRelative(user);
        var res = user.patch("/api/me/patients/" + relative.get("id").stringValue(), PROFILE_EDIT).expectStatus(200).json();
        assertThat(res.has("user")).isFalse();
        assertThat(account(user).get("fullName").stringValue()).isEqualTo("Chủ Tài Khoản");
    }

    @Test
    @DisplayName("người dùng không đổi được số điện thoại của hồ sơ chính chủ, vẫn đổi được số của người thân")
    void khongDoiSoDienThoaiHoSoChinhChu() {
        var user = client().registerUser("0918000031", "Nguyễn Văn A");
        String selfId = user.selfPatientId();
        var res = user.patch("/api/me/patients/" + selfId, body("phone", "0918000032")).expectStatus(400);
        assertThat(fields(res).has("phone")).isTrue();
        user.patch("/api/me/patients/" + selfId, body("phone", "0918000031", "fullName", "Tên Mới")).expectStatus(200);
        var relative = addRelative(user);
        user.patch("/api/me/patients/" + relative.get("id").stringValue(), body("phone", "0918000032")).expectStatus(200);
    }

    @Test
    @DisplayName("tiếp tân sửa hồ sơ chính chủ thì tài khoản đổi theo, nhưng không đổi được số đăng nhập")
    void tiepTanSuaHoSoChinhChu() {
        var staff = client().staff();
        var user = client().registerUser("0918000041", "Nguyễn Văn A");
        String selfId = user.selfPatientId();
        staff.patch("/api/staff/patients/" + selfId, body("fullName", "Tên Theo Giấy Tờ")).expectStatus(200);
        assertThat(account(user).get("fullName").stringValue()).isEqualTo("Tên Theo Giấy Tờ");
        var res = staff.patch("/api/staff/patients/" + selfId, body("phone", "0918000042")).expectStatus(400);
        assertThat(fields(res).has("phone")).isTrue();
        assertThat(account(user).get("phone").stringValue()).isEqualTo("0918000041");
    }

    @Test
    @DisplayName("hồ sơ chưa có ngày sinh thì tài khoản giữ nguyên ngày sinh")
    void hoSoChuaCoNgaySinh() {
        var staff = client().staff();
        var user = client().registerUser();
        String selfId = user.selfPatientId();
        user.patch("/api/me", body("dob", "1970-05-06")).expectStatus(200);
        jdbc.update("UPDATE patients SET dob = NULL WHERE id = ?", selfId);
        staff.patch("/api/staff/patients/" + selfId, body("fullName", "Tên Khác")).expectStatus(200);
        assertThat(account(user).get("dob").stringValue()).startsWith("1970-05-06");
    }

    @Test
    @Disabled("Task 11")
    @DisplayName("Admin sửa tài khoản thì hồ sơ chính chủ đổi theo, cả số điện thoại")
    void adminSuaTaiKhoan() {
        var admin = client().staff("admin@chuctu.vn");
        var user = client().registerUser("0918000051", "Nguyễn Văn A");
        String selfId = user.selfPatientId();
        String id = account(user).get("id").stringValue();
        admin.patch("/api/admin/accounts/" + id, body("fullName", "Tên Do Admin", "phone", "0918000052")).expectStatus(200);
        Patient self = patient(selfId);
        assertThat(self.getFullName()).isEqualTo("Tên Do Admin");
        assertThat(self.getPhone()).isEqualTo("0918000052");
    }

    @Test
    @Disabled("Task 11")
    @DisplayName("hồ sơ chính chủ đang khoá chỉ nhận số đăng nhập, mở khoá thì nhận nốt các ô còn lại")
    void hoSoDangKhoaChiNhanSoDangNhap() {
        var admin = client().staff("admin@chuctu.vn");
        var user = client().registerUser("0918000061", "Tên Cũ");
        String selfId = user.selfPatientId();
        String id = account(user).get("id").stringValue();
        admin.post("/api/admin/patients/" + selfId + "/lock", Map.of()).expectStatus(200);
        admin.patch("/api/admin/accounts/" + id, body("fullName", "Tên Mới", "phone", "0918000062")).expectStatus(200);

        Patient locked = patient(selfId);
        assertThat(locked.getFullName()).isEqualTo("Tên Cũ");
        assertThat(locked.getPhone()).isEqualTo("0918000062");

        var unlocked = admin.post("/api/admin/patients/" + selfId + "/unlock", Map.of()).expectStatus(200).json().get("patient");
        assertThat(unlocked.get("fullName").stringValue()).isEqualTo("Tên Mới");
        assertThat(unlocked.get("phone").stringValue()).isEqualTo("0918000062");
    }

    @Test
    @DisplayName("[java] hồ sơ chính chủ đang khoá chỉ nhận số đăng nhập, mở khoá thì nhận nốt các ô còn lại (đổi tài khoản qua PatientService)")
    void javaHoSoDangKhoaChiNhanSoDangNhap() {
        var admin = client().staff("admin@chuctu.vn");
        var user = client().registerUser("0918000061", "Tên Cũ");
        String selfId = user.selfPatientId();
        long userId = Long.parseLong(account(user).get("id").stringValue());
        admin.post("/api/admin/patients/" + selfId + "/lock", Map.of()).expectStatus(200);

        User changed = users.findById(userId).orElseThrow();
        changed.setFullName("Tên Mới");
        changed.setPhone("0918000062");
        users.save(changed);
        patientService.syncSelfProfile(changed);

        Patient locked = patient(selfId);
        assertThat(locked.getFullName()).isEqualTo("Tên Cũ");
        assertThat(locked.getPhone()).isEqualTo("0918000062");

        var unlocked = admin.post("/api/admin/patients/" + selfId + "/unlock", Map.of()).expectStatus(200).json().get("patient");
        assertThat(unlocked.get("fullName").stringValue()).isEqualTo("Tên Mới");
        assertThat(unlocked.get("phone").stringValue()).isEqualTo("0918000062");
        assertThat(unlocked.get("isLocked").booleanValue()).isFalse();
        assertThat(unlocked.get("lockedAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("không xoá trắng được ngày sinh, địa chỉ, giới tính của hồ sơ chính chủ")
    void khongXoaTrangHoSoChinhChu() {
        var staff = client().staff();
        var user = client().registerUser();
        String selfId = user.selfPatientId();
        assertThat(fields(user.patch("/api/me/patients/" + selfId, body("dob", null)).expectStatus(400)).has("dob")).isTrue();
        assertThat(fields(user.patch("/api/me/patients/" + selfId, body("address", "")).expectStatus(400)).has("address")).isTrue();
        assertThat(fields(staff.patch("/api/staff/patients/" + selfId, body("gender", null, "dob", null)).expectStatus(400)).has("gender"))
                .isTrue();
        Patient self = patient(selfId);
        assertThat(self.getDob()).isNotNull();
        assertThat(self.getAddress()).isNotNull();
        assertThat(self.getGender()).isNotNull();
    }

    @Test
    @DisplayName("hồ sơ người thân vẫn xoá được địa chỉ")
    void hoSoNguoiThanXoaDuocDiaChi() {
        var user = client().registerUser();
        var relative = addRelative(user, body("fullName", "Lê Thị Mẹ", "birthYear", 1945, "gender", "NU", "relationship", "KHAC", "address", "Bình Thạnh"));
        var patient = user.patch("/api/me/patients/" + relative.get("id").stringValue(), body("address", "")).expectStatus(200).json().get("patient");
        assertThat(patient.get("address").isNull()).isTrue();
    }

    @Test
    @Disabled("Task 11")
    @DisplayName("Admin không xoá trắng được địa chỉ tài khoản")
    void adminKhongXoaTrangDiaChi() {
        var admin = client().staff("admin@chuctu.vn");
        var user = client().registerUser();
        String id = account(user).get("id").stringValue();
        admin.patch("/api/admin/accounts/" + id, body("address", "")).expectStatus(400);
        admin.patch("/api/admin/accounts/" + id, body("address", null)).expectStatus(400);
        assertThat(account(user).get("address").stringValue()).isEqualTo("Bình Thạnh");
    }

    // ---------- Hai nơi cùng sửa một người (không đè mất thay đổi) ----------

    @Test
    @Disabled("Task 11")
    @DisplayName("Admin vừa đổi tên thì người dùng lưu hồ sơ bản cũ bị từ chối, tải lại rồi lưu được")
    void adminVuaDoiTen() {
        var admin = client().staff("admin@chuctu.vn");
        var user = client().registerUser();
        String selfId = user.selfPatientId();
        var stale = user.get("/api/me/patients/" + selfId).expectStatus(200).json().get("patient");
        String id = account(user).get("id").stringValue();
        admin.patch("/api/admin/accounts/" + id, body("fullName", "Tên Do Admin")).expectStatus(200);

        var res = user.patch("/api/me/patients/" + selfId,
                body("fullName", "Tên Cũ Sửa", "expectedUpdatedAt", stale.get("updatedAt").stringValue())).expectStatus(409);
        assertThat(errorCode(res)).isEqualTo("STALE");
        assertThat(patient(selfId).getFullName()).isEqualTo("Tên Do Admin");

        var fresh = user.get("/api/me/patients/" + selfId).expectStatus(200).json().get("patient");
        user.patch("/api/me/patients/" + selfId,
                body("fullName", "Tên Mới", "expectedUpdatedAt", fresh.get("updatedAt").stringValue())).expectStatus(200);
    }

    @Test
    @Disabled("Task 11")
    @DisplayName("người dùng vừa đổi hồ sơ thì Admin lưu tài khoản bản cũ bị từ chối")
    void nguoiDungVuaDoiHoSo() {
        var admin = client().staff("admin@chuctu.vn");
        var user = client().registerUser();
        String id = account(user).get("id").stringValue();
        var stale = admin.get("/api/admin/accounts/" + id).expectStatus(200).json().get("account");
        user.patch("/api/me/patients/" + user.selfPatientId(), body("fullName", "Tên Do Người Dùng")).expectStatus(200);
        var res = admin.patch("/api/admin/accounts/" + id,
                body("fullName", "Tên Admin", "expectedUpdatedAt", stale.get("infoUpdatedAt").stringValue())).expectStatus(409);
        assertThat(errorCode(res)).isEqualTo("STALE");
    }

    @Test
    @DisplayName("tiếp tân và trang Thông tin tài khoản cũng phát hiện bản cũ")
    void tiepTanVaTrangThongTinPhatHienBanCu() {
        var staff = client().staff();
        var user = client().registerUser();
        String selfId = user.selfPatientId();
        var staleMe = account(user);
        var stalePatient = staff.get("/api/staff/patients/" + selfId).expectStatus(200).json().get("patient");

        String stamp = stalePatient.get("updatedAt").stringValue();
        staff.patch("/api/staff/patients/" + selfId, body("fullName", "Tên Quầy", "expectedUpdatedAt", stamp)).expectStatus(200);
        staff.patch("/api/staff/patients/" + selfId, body("fullName", "Tên Quầy 2", "expectedUpdatedAt", stamp)).expectStatus(409);
        user.patch("/api/me", body("fullName", "Tên Cũ", "expectedUpdatedAt", staleMe.get("infoUpdatedAt").stringValue())).expectStatus(409);
    }

    @Test
    @DisplayName("đăng nhập lại không làm bản đang sửa thành bản cũ")
    void dangNhapLaiKhongLamBanCu() {
        var user = client().registerUser("0918000071", "Nguyễn Văn A");
        String infoUpdatedAt = account(user).get("infoUpdatedAt").stringValue();
        user.post("/api/auth/login", body("phone", "0918000071")).expectStatus(200);
        user.patch("/api/me", body("fullName", "Tên Mới", "expectedUpdatedAt", infoUpdatedAt)).expectStatus(200);
    }

    @Test
    @DisplayName("không gửi mốc thời gian thì vẫn lưu như thường")
    void khongGuiMocThoiGian() {
        var user = client().registerUser();
        user.patch("/api/me/patients/" + user.selfPatientId(), body("fullName", "Tên Mới")).expectStatus(200);
        user.patch("/api/me", body("fullName", "Tên Khác", "expectedUpdatedAt", "khong-phai-ngay")).expectStatus(400);
    }

    @Test
    @DisplayName("[java] mốc thời gian so theo mili giây, múi giờ khác vẫn khớp cùng thời điểm")
    void javaMocThoiGianTheoMiliGiay() {
        var user = client().registerUser();
        String selfId = user.selfPatientId();
        String updatedAt = user.get("/api/me/patients/" + selfId).expectStatus(200).json().get("patient").get("updatedAt").stringValue();
        Instant at = Instant.parse(updatedAt);
        String sameInstant = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.atOffset(ZoneOffset.ofHours(7)));
        String oneMsLater = DateTimeFormatter.ISO_INSTANT.format(at.plusMillis(1));
        var stale = user.patch("/api/me/patients/" + selfId, body("fullName", "Tên A", "expectedUpdatedAt", oneMsLater)).expectStatus(409);
        assertThat(errorCode(stale)).isEqualTo("STALE");
        user.patch("/api/me/patients/" + selfId, body("fullName", "Tên A", "expectedUpdatedAt", sameInstant)).expectStatus(200);
    }

    // ---------- Ngày tháng và năm sinh ----------

    @Test
    @DisplayName("từ chối ngày không có thật, không tự đẩy sang tháng sau")
    void tuChoiNgayKhongCoThat() {
        var user = client().registerUser();
        for (String dob : new String[] {"1950-02-30", "1950-04-31", "1951-02-29"}) {
            var res = user.post("/api/me/patients", body("fullName", "Lê Thị Mẹ", "gender", "NU", "relationship", "KHAC", "dob", dob))
                    .expectStatus(400);
            assertThat(fields(res).has("dob")).as(dob).isTrue();
        }
        user.post("/api/me/patients", body("fullName", "Lê Thị Mẹ", "gender", "NU", "relationship", "KHAC", "dob", "1952-02-29")).expectStatus(201);
        var booked = book(user, user.selfPatientId(), "2026-02-30").expectStatus(400);
        assertThat(fields(booked).has("slotDate")).isTrue();
    }

    @Test
    @DisplayName("[java] từ chối ngày sinh không có thật, không tự đẩy sang tháng sau")
    void javaTuChoiNgaySinhKhongCoThat() {
        var user = client().registerUser();
        for (String dob : new String[] {"1950-02-30", "1950-04-31", "1951-02-29"}) {
            var res = user.post("/api/me/patients", body("fullName", "Lê Thị Mẹ", "gender", "NU", "relationship", "KHAC", "dob", dob))
                    .expectStatus(400);
            assertThat(fields(res).get("dob").stringValue()).as(dob).isEqualTo("Ngày không hợp lệ.");
        }
        var ok = user.post("/api/me/patients", body("fullName", "Lê Thị Mẹ", "gender", "NU", "relationship", "KHAC", "dob", "1952-02-29"))
                .expectStatus(201).json().get("patient");
        assertThat(ok.get("dob").stringValue()).isEqualTo("1952-02-29T00:00:00.000Z");
        assertThat(ok.get("birthYear").intValue()).isEqualTo(1952);
    }

    @Test
    @DisplayName("[java] ngày sinh phải trước hôm nay")
    void javaNgaySinhPhaiTruocHomNay() {
        var user = client().registerUser();
        String today = LocalDate.now(ZoneOffset.ofHours(7)).toString();
        var res = user.post("/api/me/patients", body("fullName", "Lê Thị Mẹ", "gender", "NU", "relationship", "KHAC", "dob", today))
                .expectStatus(400);
        assertThat(fields(res).get("dob").stringValue()).isEqualTo("Ngày sinh phải trước hôm nay.");
    }

    @Test
    @DisplayName("có ngày sinh thì năm sinh đi theo ngày sinh")
    void coNgaySinhThiNamSinhDiTheo() {
        var staff = client().staff();
        var user = client().registerUser();
        var created = addRelative(user, body("fullName", "Lê Thị Mẹ", "gender", "NU", "relationship", "KHAC", "dob", "1950-01-02", "birthYear", 1999));
        assertThat(created.get("birthYear").intValue()).isEqualTo(1950);
        String id = created.get("id").stringValue();
        var res = staff.patch("/api/staff/patients/" + id, body("birthYear", 1970)).expectStatus(400);
        assertThat(fields(res).has("birthYear")).isTrue();
        staff.patch("/api/staff/patients/" + id, body("birthYear", 1950)).expectStatus(200);
        assertThat(patient(id).getBirthYear()).isEqualTo(1950);
    }

    @Test
    @DisplayName("[java] năm sinh nhận chuỗi số, báo lỗi theo từng trường hợp")
    void javaNamSinh() {
        var user = client().registerUser();
        var base = body("fullName", "Lê Thị Mẹ", "gender", "NU", "relationship", "KHAC");
        var ok = user.post("/api/me/patients", with(base, "birthYear", "1950")).expectStatus(201).json().get("patient");
        assertThat(ok.get("birthYear").intValue()).isEqualTo(1950);
        assertThat(ok.get("dob").isNull()).isTrue();
        assertThat(fields(user.post("/api/me/patients", with(base, "birthYear", "abc")).expectStatus(400)).get("birthYear").stringValue())
                .isEqualTo("Vui lòng chọn năm sinh.");
        assertThat(fields(user.post("/api/me/patients", with(base, "birthYear", 1800)).expectStatus(400)).get("birthYear").stringValue())
                .isEqualTo("Năm sinh chưa đúng.");
        assertThat(fields(user.post("/api/me/patients", with(base, "birthYear", 1950.5)).expectStatus(400)).get("birthYear").stringValue())
                .isEqualTo("Năm sinh chưa đúng.");
        assertThat(fields(user.post("/api/me/patients", with(base, "birthYear", 3000)).expectStatus(400)).get("birthYear").stringValue())
                .isEqualTo("Năm sinh chưa đúng.");
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    // ---------- Hồ sơ chính chủ thiếu thông tin (tài khoản do Admin tạo) ----------

    @Test
    @Disabled("Task 11")
    @DisplayName("phải bổ sung ngày sinh, giới tính, địa chỉ rồi mới đặt được lịch")
    void phaiBoSungThongTinRoiMoiDatLich() {
        var admin = client().staff("admin@chuctu.vn");
        admin.post("/api/admin/accounts", body("fullName", "Khách Admin Tạo", "phone", "0918000081", "role", "USER")).expectStatus(201);
        var user = client();
        var login = user.post("/api/auth/login", body("phone", "0918000081")).expectStatus(200);
        user.post("/api/auth/login/verify", body("phone", "0918000081", "code", login.json().get("devCode").stringValue())).expectStatus(200);
        String selfId = user.selfPatientId();

        var res = book(user, selfId).expectStatus(400);
        assertThat(errorCode(res)).isEqualTo("PROFILE_INCOMPLETE");
        user.patch("/api/me/patients/" + selfId, body("dob", "1970-01-01", "gender", "NAM")).expectStatus(200);
        book(user, selfId).expectStatus(400);
        user.patch("/api/me/patients/" + selfId, body("address", "Bình Thạnh")).expectStatus(200);
        book(user, selfId).expectStatus(201);
    }

    @Test
    @Disabled("Task 11")
    @DisplayName("tiếp tân vẫn đặt hộ được khi hồ sơ thiếu thông tin")
    void tiepTanVanDatHoDuoc() {
        var admin = client().staff("admin@chuctu.vn");
        var account = admin.post("/api/admin/accounts", body("fullName", "Khách Tại Quầy", "phone", "0918000091", "role", "USER"))
                .expectStatus(201).json().get("account");
        long selfId = jdbc.queryForObject("SELECT id FROM patients WHERE user_id = ?", Long.class, Long.parseLong(account.get("id").stringValue()));
        assertThat(patients.findById(selfId).orElseThrow().getAddress()).isNull();
        var staff = client().staff();
        staff.post("/api/staff/appointments",
                body("patientId", String.valueOf(selfId), "serviceType", "CHAM_CUU", "slotDate", futureDate(1), "slotStart", "09:00"))
                .expectStatus(201);
    }

    // ---------- Hồ sơ khám của tài khoản (UC.1.6 – UC.1.8) ----------

    @Test
    @DisplayName("tài khoản chỉ có một hồ sơ chính chủ")
    void chiMotHoSoChinhChu() {
        var user = client().registerUser();
        user.post("/api/me/patients", with(RELATIVE, "relationship", "BAN_THAN")).expectStatus(400);
        var relative = addRelative(user);
        user.patch("/api/me/patients/" + relative.get("id").stringValue(), body("relationship", "BAN_THAN")).expectStatus(400);
    }

    @Test
    @DisplayName("không đổi quan hệ của hồ sơ chính chủ")
    void khongDoiQuanHeHoSoChinhChu() {
        var user = client().registerUser();
        user.patch("/api/me/patients/" + user.selfPatientId(), body("relationship", "CON")).expectStatus(400);
    }

    @Test
    @DisplayName("hồ sơ cần ngày sinh hoặc năm sinh")
    void hoSoCanNgaySinhHoacNamSinh() {
        var user = client().registerUser();
        var res = user.post("/api/me/patients", body("fullName", "Lê Văn Con", "gender", "NAM", "relationship", "CON")).expectStatus(400);
        assertThat(fields(res).get("dob").stringValue()).isEqualTo("Cô chú vui lòng chọn ngày sinh hoặc năm sinh.");
    }

    @Test
    @DisplayName("[java] thiếu họ tên thì không báo thêm lỗi ngày sinh (zod bỏ qua refine khi có lỗi kiểu)")
    void javaThieuHoTenKhongBaoNgaySinh() {
        var user = client().registerUser();
        var res = user.post("/api/me/patients", body("gender", "NAM", "relationship", "CON")).expectStatus(400);
        assertThat(fields(res).has("fullName")).isTrue();
        assertThat(fields(res).has("dob")).isFalse();
        // lỗi chuỗi (chỉ là "dirty") thì refine vẫn chạy
        var dirty = user.post("/api/me/patients", body("fullName", "A", "gender", "NAM", "relationship", "CON")).expectStatus(400);
        assertThat(fields(dirty).has("fullName")).isTrue();
        assertThat(fields(dirty).has("dob")).isTrue();
    }

    @Test
    @DisplayName("sửa hồ sơ người thân của mình, ô không gửi thì giữ nguyên")
    void suaHoSoNguoiThanCuaMinh() {
        var user = client().registerUser();
        var relative = addRelative(user, body("fullName", "Lê Thị Mẹ", "birthYear", 1945, "gender", "NU", "relationship", "KHAC", "address", "Bình Thạnh"));
        String id = relative.get("id").stringValue();
        var patient = user.patch("/api/me/patients/" + id, body("fullName", "Lê Thị Bà")).expectStatus(200).json().get("patient");
        assertThat(patient.get("fullName").stringValue()).isEqualTo("Lê Thị Bà");
        assertThat(patient.get("address").stringValue()).isEqualTo("Bình Thạnh");
        assertThat(user.get("/api/me/patients/" + id).expectStatus(200).json().get("patient").get("fullName").stringValue())
                .isEqualTo("Lê Thị Bà");
    }

    @Test
    @DisplayName("không xem, sửa, xoá hay đặt lịch được cho hồ sơ của tài khoản khác")
    void khongDungDuocHoSoTaiKhoanKhac() {
        var owner = client().registerUser("0918000011", "Chủ Hồ Sơ");
        var other = client().registerUser("0918000012", "Người Khác");
        var relative = addRelative(owner);
        String id = relative.get("id").stringValue();

        other.get("/api/me/patients/" + id).expectStatus(404);
        other.patch("/api/me/patients/" + id, body("fullName", "Tên Lạ")).expectStatus(404);
        other.delete("/api/me/patients/" + id).expectStatus(404);
        book(other, id).expectStatus(404);
        assertThat(owner.get("/api/me/patients/" + id).expectStatus(200).json().get("patient").get("fullName").stringValue())
                .isEqualTo("Lê Thị Mẹ");
    }

    @Test
    @DisplayName("[java] không xem, sửa, xoá được hồ sơ của tài khoản khác")
    void javaKhongDungDuocHoSoTaiKhoanKhac() {
        var owner = client().registerUser("0918000011", "Chủ Hồ Sơ");
        var other = client().registerUser("0918000012", "Người Khác");
        String id = addRelative(owner).get("id").stringValue();

        var res = other.get("/api/me/patients/" + id).expectStatus(404);
        assertThat(res.json().get("error").get("message").stringValue()).isEqualTo("Không tìm thấy hồ sơ khám.");
        other.patch("/api/me/patients/" + id, body("fullName", "Tên Lạ")).expectStatus(404);
        other.delete("/api/me/patients/" + id).expectStatus(404);
        assertThat(patient(id).getFullName()).isEqualTo("Lê Thị Mẹ");
        assertThat(patient(id).getUserId()).isEqualTo(Long.parseLong(account(owner).get("id").stringValue()));
    }

    @Test
    @DisplayName("hồ sơ bị khoá thì không sửa được và không đặt lịch được")
    void hoSoBiKhoa() {
        var admin = client().staff("admin@chuctu.vn");
        var user = client().registerUser();
        var relative = addRelative(user);
        String id = relative.get("id").stringValue();
        admin.post("/api/admin/patients/" + id + "/lock", Map.of()).expectStatus(200);
        user.patch("/api/me/patients/" + id, body("fullName", "Tên Khác")).expectStatus(403);
        book(user, id).expectStatus(403);
    }

    @Test
    @DisplayName("[java] hồ sơ bị khoá thì không sửa được")
    void javaHoSoBiKhoaKhongSuaDuoc() {
        var admin = client().staff("admin@chuctu.vn");
        var user = client().registerUser();
        String id = addRelative(user).get("id").stringValue();
        admin.post("/api/admin/patients/" + id + "/lock", Map.of()).expectStatus(200);
        var res = user.patch("/api/me/patients/" + id, body("fullName", "Tên Khác")).expectStatus(403);
        assertThat(errorCode(res)).isEqualTo("FORBIDDEN");
        assertThat(res.json().get("error").get("message").stringValue())
                .isEqualTo("Hồ sơ này đang bị phòng khám khoá nên không sửa được. Cô chú gọi phòng khám để được hỗ trợ.");
        assertThat(patient(id).getFullName()).isEqualTo("Lê Thị Mẹ");
    }

    @Test
    @DisplayName("không xoá được hồ sơ chính chủ")
    void khongXoaDuocHoSoChinhChu() {
        var user = client().registerUser();
        user.delete("/api/me/patients/" + user.selfPatientId()).expectStatus(400);
    }

    @Test
    @DisplayName("hồ sơ chưa từng khám thì xoá hẳn")
    void hoSoChuaKhamThiXoaHan() {
        var user = client().registerUser();
        var relative = addRelative(user);
        String id = relative.get("id").stringValue();
        assertThat(user.delete("/api/me/patients/" + id).expectStatus(200).text()).isEqualTo("{\"deleted\":true}");
        assertThat(patients.findById(Long.parseLong(id))).isEmpty();
    }

    @Test
    @DisplayName("hồ sơ còn phiếu chưa hoàn thành thì chưa xoá được")
    void hoSoConPhieuChuaHoanThanh() {
        var user = client().registerUser();
        var relative = addRelative(user);
        String id = relative.get("id").stringValue();
        book(user, id).expectStatus(201);
        var res = user.delete("/api/me/patients/" + id).expectStatus(409);
        assertThat(errorCode(res)).isEqualTo("PROFILE_IN_USE");
    }

    @Test
    @DisplayName("[java] hồ sơ còn phiếu chưa hoàn thành thì chưa xoá được (phiếu chèn thẳng vào database)")
    void javaHoSoConPhieuChuaHoanThanh() {
        var user = client().registerUser();
        long userId = Long.parseLong(account(user).get("id").stringValue());
        long id = Long.parseLong(addRelative(user).get("id").stringValue());
        for (String status : new String[] {"BOOKED", "CHECKED_IN", "IN_PROGRESS"}) {
            long appt = insertAppointment(id, userId, futureDate(2), status);
            var res = user.delete("/api/me/patients/" + id).expectStatus(409);
            assertThat(errorCode(res)).as(status).isEqualTo("PROFILE_IN_USE");
            assertThat(res.json().get("error").get("message").stringValue())
                    .isEqualTo("Hồ sơ này còn phiếu khám chưa hoàn thành. Cô chú huỷ phiếu khám trước rồi xoá hồ sơ.");
            jdbc.update("DELETE FROM appointments WHERE id = ?", appt);
        }
        assertThat(patients.findById(id)).isPresent();
    }

    @Test
    @DisplayName("hồ sơ đã có phiếu khám thì tách khỏi tài khoản, phòng khám vẫn giữ (BR.38)")
    void hoSoDaCoPhieuKham() {
        var user = client().registerUser();
        var relative = addRelative(user);
        String id = relative.get("id").stringValue();
        var appt = book(user, id).expectStatus(201).json().get("appointment");
        user.post("/api/me/appointments/" + appt.get("id").stringValue() + "/cancel", Map.of()).expectStatus(200);

        assertThat(user.delete("/api/me/patients/" + id).expectStatus(200).text()).isEqualTo("{\"detached\":true}");
        assertThat(patient(id).getUserId()).isNull();
        for (JsonNode p : user.get("/api/me/patients").expectStatus(200).json().get("items")) {
            assertThat(p.get("id").stringValue()).isNotEqualTo(id);
        }
    }

    @Test
    @DisplayName("[java] hồ sơ đã có phiếu khám thì tách khỏi tài khoản, phòng khám vẫn giữ (BR.38)")
    void javaHoSoDaCoPhieuKham() {
        var user = client().registerUser();
        long userId = Long.parseLong(account(user).get("id").stringValue());
        String id = addRelative(user).get("id").stringValue();
        insertAppointment(Long.parseLong(id), userId, futureDate(2), "CANCELLED");

        assertThat(user.delete("/api/me/patients/" + id).expectStatus(200).text()).isEqualTo("{\"detached\":true}");
        assertThat(patient(id).getUserId()).isNull();
        for (JsonNode p : user.get("/api/me/patients").expectStatus(200).json().get("items")) {
            assertThat(p.get("id").stringValue()).isNotEqualTo(id);
        }
        user.get("/api/me/patients/" + id).expectStatus(404);
    }

    @Test
    @DisplayName("[java] danh sách hồ sơ có lần khám gần nhất theo phiếu đã hoàn thành")
    void javaDanhSachCoLanKhamGanNhat() {
        var user = client().registerUser();
        long userId = Long.parseLong(account(user).get("id").stringValue());
        long selfId = Long.parseLong(user.selfPatientId());
        String relativeId = addRelative(user).get("id").stringValue();
        insertAppointment(selfId, userId, "2026-03-01", "COMPLETED");
        insertAppointment(selfId, userId, "2026-05-09", "COMPLETED");
        insertAppointment(selfId, userId, "2026-08-01", "CANCELLED");

        var list = user.get("/api/me/patients").expectStatus(200).json();
        assertThat(list.get("max").intValue()).isEqualTo(10);
        for (JsonNode p : list.get("items")) {
            if (p.get("id").stringValue().equals(String.valueOf(selfId))) {
                assertThat(p.get("lastVisitDate").stringValue()).isEqualTo("2026-05-09T00:00:00.000Z");
            } else {
                assertThat(p.get("id").stringValue()).isEqualTo(relativeId);
                assertThat(p.get("lastVisitDate").isNull()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("[java] tối đa 10 hồ sơ cho mỗi tài khoản")
    void javaToiDa10HoSo() {
        var user = client().registerUser();
        for (int i = 0; i < 9; i++) addRelative(user);
        var res = user.post("/api/me/patients", RELATIVE).expectStatus(400);
        assertThat(errorCode(res)).isEqualTo("PROFILE_LIMIT");
    }

    @Test
    @DisplayName("[java] hồ sơ trả về đủ trường: mã bệnh nhân, ngày sinh nửa đêm UTC, năm sinh theo ngày sinh")
    void javaHinhDangHoSo() {
        var user = client().registerUser("0918000101", "Nguyễn Văn A");
        var me = user.get("/api/me").expectStatus(200).json();
        assertThat(me.get("patientCode").stringValue()).matches("^CT-BN-\\d{2}-0001$");
        var self = user.get("/api/me/patients/" + user.selfPatientId()).expectStatus(200).json().get("patient");
        assertThat(self.get("dob").stringValue()).isEqualTo("1960-01-01T00:00:00.000Z");
        assertThat(self.get("birthYear").intValue()).isEqualTo(1960);
        assertThat(self.get("relationship").stringValue()).isEqualTo("BAN_THAN");
        assertThat(self.get("userId").stringValue()).isEqualTo(me.get("user").get("id").stringValue());
        assertThat(self.get("isLocked").booleanValue()).isFalse();
        assertThat(self.get("lockedAt").isNull()).isTrue();
        assertThat(self.get("createdAt").stringValue()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(self.get("nationalId").isNull()).isTrue();
        // mã bệnh nhân thứ hai cùng năm tăng dần
        var relative = addRelative(user);
        assertThat(relative.get("patientCode").stringValue()).matches("^CT-BN-\\d{2}-0002$");
    }

    @Test
    @DisplayName("[java] hồ sơ có birthYear rỗng thì lấy năm của ngày sinh")
    void javaBirthYearLayTuNgaySinh() {
        var user = client().registerUser();
        String selfId = user.selfPatientId();
        jdbc.update("UPDATE patients SET birth_year = NULL WHERE id = ?", selfId);
        var self = user.get("/api/me/patients/" + selfId).expectStatus(200).json().get("patient");
        assertThat(self.get("birthYear").intValue()).isEqualTo(1960);
    }

    @Test
    @DisplayName("[java] danh sách hồ sơ xếp theo quan hệ rồi ngày tạo")
    void javaThuTuDanhSachHoSo() {
        var user = client().registerUser();
        addRelative(user, with(RELATIVE, "relationship", "VO_CHONG"));
        addRelative(user, with(RELATIVE, "relationship", "CON"));
        var items = user.get("/api/me/patients").expectStatus(200).json().get("items");
        var order = new java.util.ArrayList<String>();
        items.forEach(p -> order.add(p.get("relationship").stringValue()));
        assertThat(order).containsExactly("BAN_THAN", "CON", "VO_CHONG");
    }

    @Test
    @DisplayName("[java] hồ sơ người thân nhận số điện thoại, số căn cước, email và chuẩn hoá")
    void javaChuanHoaSoDienThoaiEmail() {
        var user = client().registerUser();
        var created = addRelative(user, with(with(with(RELATIVE, "phone", "+84 916 000 001"), "nationalId", " 079052000123 "), "email", " Me@Email.COM "));
        assertThat(created.get("phone").stringValue()).isEqualTo("0916000001");
        assertThat(created.get("nationalId").stringValue()).isEqualTo("079052000123");
        assertThat(created.get("email").stringValue()).isEqualTo("me@email.com");
        var bad = user.post("/api/me/patients", with(with(with(RELATIVE, "phone", "123"), "nationalId", "12"), "email", "x")).expectStatus(400);
        assertThat(fields(bad).has("phone")).isTrue();
        assertThat(fields(bad).get("nationalId").stringValue()).isEqualTo("Số căn cước gồm 12 chữ số (hoặc 9 số với CMND cũ).");
        assertThat(fields(bad).get("email").stringValue()).isEqualTo("Email chưa đúng định dạng.");
        var wrongType = user.post("/api/me/patients", with(RELATIVE, "phone", 123)).expectStatus(400);
        assertThat(fields(wrongType).get("phone").stringValue()).isEqualTo("Invalid input");
    }
}
