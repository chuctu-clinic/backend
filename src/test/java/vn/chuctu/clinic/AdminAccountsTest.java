package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Cổng từ test/admin-accounts.test.js (quản lý tài khoản của Admin), kèm các test [java] cho điểm Node không có. */
class AdminAccountsTest extends IntegrationTest {

    private static final String TEMP = "MatKhauTam@1";

    private TestClient admin() {
        return client().staff("admin@chuctu.vn");
    }

    private TestClient.Result staffLogin(String identifier, String password) {
        return client().post("/api/auth/staff/login", body("identifier", identifier, "password", password));
    }

    private static Map<String, Object> newAccount(Object... overrides) {
        Map<String, Object> out = body("fullName", "Tài Khoản Mới", "phone", "0920000001", "role", "USER");
        out.putAll(body(overrides));
        return out;
    }

    private String idOf(String phone) {
        return String.valueOf(jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone));
    }

    private String idOfRole(String role) {
        return String.valueOf(jdbc.queryForObject("SELECT id FROM users WHERE role = ?", Long.class, role));
    }

    private String column(String column, String id) {
        return jdbc.queryForObject("SELECT " + column + " FROM users WHERE id = ?", String.class, Long.parseLong(id));
    }

    private static String errorCode(TestClient.Result res) {
        return res.json().get("error").get("code").stringValue();
    }

    private static JsonNode fields(TestClient.Result res) {
        return res.json().get("error").get("fields");
    }

    private static List<String> ids(JsonNode list) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : list.get("items")) out.add(item.get("id").stringValue());
        return out;
    }

    private static JsonNode list(TestClient admin, String query) {
        return admin.get("/api/admin/accounts?" + query.replace(" ", "%20")).expectStatus(200).json();
    }

    // ---------- Phân quyền Quản lý tài khoản (BR.05, BR.10, BR.31) ----------

    @Test
    @DisplayName("chỉ Admin dùng được")
    void chiAdminDungDuoc() {
        var receptionist = client().staff();
        var user = client().registerUser();
        client().get("/api/admin/accounts").expectStatus(401);
        receptionist.get("/api/admin/accounts").expectStatus(403);
        user.get("/api/admin/accounts").expectStatus(403);
        receptionist.post("/api/admin/accounts", newAccount()).expectStatus(403);
        user.put("/api/admin/accounts/" + idOf("0911000001") + "/role", body("role", "ADMIN", "tempPassword", TEMP)).expectStatus(403);
        assertThat(column("role", idOf("0911000001"))).isEqualTo("USER");
    }

    // ---------- Tạo và sửa tài khoản (UC.8.1 – UC.8.3) ----------

    @Test
    @DisplayName("tài khoản người dùng có sẵn hồ sơ chính chủ, không có mật khẩu")
    void nguoiDungCoHoSoChinhChu() {
        var account = admin().post("/api/admin/accounts", newAccount()).expectStatus(201).json().get("account");
        assertThat(account.get("role").stringValue()).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM patients WHERE user_id = ? AND relationship = 'BAN_THAN'", Integer.class,
                Long.parseLong(account.get("id").stringValue()))).isEqualTo(1);
        assertThat(column("password_hash", account.get("id").stringValue())).isNull();
    }

    @Test
    @DisplayName("tài khoản nội bộ cần mật khẩu tạm và phải đổi khi đăng nhập lần đầu (BR.33)")
    void noiBoCanMatKhauTam() {
        var admin = admin();
        var data = newAccount("role", "RECEPTIONIST", "email", "moi@chuctu.vn");
        var res = admin.post("/api/admin/accounts", data).expectStatus(400);
        assertThat(fields(res).has("tempPassword")).isTrue();
        var withShort = new LinkedHashMap<>(data);
        withShort.put("tempPassword", "ngan");
        admin.post("/api/admin/accounts", withShort).expectStatus(400);

        var withTemp = new LinkedHashMap<>(data);
        withTemp.put("tempPassword", TEMP);
        admin.post("/api/admin/accounts", withTemp).expectStatus(201);
        assertThat(column("must_change_password", idOf("0920000001"))).isEqualTo("1");
        staffLogin("moi@chuctu.vn", TEMP).expectStatus(200);
        staffLogin("moi@chuctu.vn", "SaiMatKhau@1").expectStatus(401);
    }

    @Test
    @DisplayName("tài khoản nội bộ bắt buộc có email để dùng Quên mật khẩu")
    void noiBoBatBuocEmail() {
        var admin = admin();
        for (String role : List.of("RECEPTIONIST", "ADMIN")) {
            var res = admin.post("/api/admin/accounts", newAccount("role", role, "tempPassword", TEMP)).expectStatus(400);
            assertThat(errorCode(res)).isEqualTo("EMAIL_REQUIRED");
            assertThat(fields(res).has("email")).isTrue();
            admin.post("/api/admin/accounts", newAccount("role", role, "tempPassword", TEMP, "email", "")).expectStatus(400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone = '0920000001'", Integer.class)).isZero();
        // Tài khoản người dùng vẫn không cần email
        admin.post("/api/admin/accounts", newAccount()).expectStatus(201);
    }

    @Test
    @DisplayName("không xoá được email của tài khoản nội bộ, tài khoản người dùng thì được")
    void khongXoaEmailNoiBo() {
        var admin = admin();
        String receptionist = idOfRole("RECEPTIONIST");
        admin.patch("/api/admin/accounts/" + receptionist, "{\"email\":\"\"}").expectStatus(400);
        admin.patch("/api/admin/accounts/" + receptionist, "{\"email\":null}").expectStatus(400);
        assertThat(column("email", receptionist)).isEqualTo("letan@chuctu.vn");

        var account = admin.post("/api/admin/accounts", newAccount("email", "nguoidung@chuctu.vn")).expectStatus(201).json().get("account");
        admin.patch("/api/admin/accounts/" + account.get("id").stringValue(), "{\"email\":\"\"}").expectStatus(200);
    }

    @Test
    @DisplayName("số điện thoại và email không được trùng tài khoản khác")
    void khongTrungSoVaEmail() {
        var admin = admin();
        var first = admin.post("/api/admin/accounts", newAccount("role", "RECEPTIONIST", "email", "a@chuctu.vn", "tempPassword", TEMP))
                .expectStatus(201).json().get("account");
        var second = admin.post("/api/admin/accounts", newAccount("phone", "0920000002")).expectStatus(201).json().get("account");

        var dup = admin.post("/api/admin/accounts", newAccount()).expectStatus(409);
        assertThat(errorCode(dup)).isEqualTo("PHONE_TAKEN");
        var phone = admin.patch("/api/admin/accounts/" + second.get("id").stringValue(), body("phone", first.get("phone").stringValue()))
                .expectStatus(409);
        assertThat(errorCode(phone)).isEqualTo("PHONE_TAKEN");
        var email = admin.patch("/api/admin/accounts/" + second.get("id").stringValue(), body("email", "a@chuctu.vn")).expectStatus(409);
        assertThat(errorCode(email)).isEqualTo("EMAIL_TAKEN");
        // Giữ nguyên số của chính mình thì không tính là trùng
        admin.patch("/api/admin/accounts/" + first.get("id").stringValue(),
                body("phone", first.get("phone").stringValue(), "fullName", "Tên Đã Sửa")).expectStatus(200);
    }

    @Test
    @DisplayName("lọc danh sách theo vai trò, trạng thái, từ khoá")
    void locDanhSach() {
        var admin = admin();
        var account = admin.post("/api/admin/accounts", newAccount("fullName", "Người Cần Tìm")).expectStatus(201).json().get("account");
        String id = account.get("id").stringValue();
        admin.post("/api/admin/accounts/" + id + "/lock", Map.of()).expectStatus(200);

        assertThat(ids(list(admin, "q=Cần Tìm"))).containsExactly(id);
        assertThat(ids(list(admin, "status=locked"))).containsExactly(id);
        for (JsonNode item : list(admin, "role=ADMIN").get("items")) assertThat(item.get("role").stringValue()).isEqualTo("ADMIN");
        assertThat(ids(list(admin, "status=active"))).doesNotContain(id);
        assertThat(list(admin, "").get("roleCounts").get("USER").intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("[java] ký tự % và _ trong từ khoá tìm đúng nghĩa đen")
    void javaTimKiemKyTuDacBiet() {
        var admin = admin();
        var plain = admin.post("/api/admin/accounts", newAccount("fullName", "Nguyen Van Abc")).expectStatus(201).json().get("account");
        var literal = admin.post("/api/admin/accounts", newAccount("phone", "0920000002", "fullName", "Giam 50%_off")).expectStatus(201)
                .json().get("account");
        assertThat(ids(list(admin, "q=50%25_off"))).containsExactly(literal.get("id").stringValue());
        assertThat(ids(list(admin, "q=%25"))).containsExactly(literal.get("id").stringValue());
        assertThat(ids(list(admin, "q=_"))).containsExactly(literal.get("id").stringValue());
        assertThat(ids(list(admin, "q=Nguyen"))).containsExactly(plain.get("id").stringValue());
    }

    @Test
    @DisplayName("[java] total và trang hai của danh sách tài khoản")
    void javaPhanTrang() {
        var admin = admin();
        for (int i = 1; i <= 3; i++) {
            admin.post("/api/admin/accounts", newAccount("phone", "092000010" + i, "fullName", "Khach " + i)).expectStatus(201);
        }
        // 2 tài khoản nội bộ mẫu + 3 người dùng
        var first = list(admin, "limit=2");
        assertThat(first.get("total").intValue()).isEqualTo(5);
        assertThat(first.get("page").intValue()).isEqualTo(1);
        assertThat(first.get("items").size()).isEqualTo(2);
        var second = list(admin, "limit=2&page=2");
        assertThat(second.get("page").intValue()).isEqualTo(2);
        assertThat(second.get("total").intValue()).isEqualTo(5);
        assertThat(second.get("items").size()).isEqualTo(2);
        assertThat(ids(second)).doesNotContainAnyElementsOf(ids(first));
        assertThat(list(admin, "limit=2&page=3").get("items").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("không tìm thấy tài khoản thì báo 404")
    void khongTimThay() {
        admin().get("/api/admin/accounts/000000000000000000000000").expectStatus(404);
    }

    // ---------- Khoá tài khoản (BR.33) ----------

    @Test
    @DisplayName("khoá xong thì phiên đang mở bị huỷ, không đăng nhập được; mở khoá thì đăng nhập lại được")
    void khoaVaMoKhoa() {
        var admin = admin();
        var user = client().registerUser();
        user.get("/api/me").expectStatus(200);
        String id = idOf("0911000001");

        admin.post("/api/admin/accounts/" + id + "/lock", Map.of()).expectStatus(200);
        user.get("/api/me").expectStatus(401);
        user.post("/api/auth/login", body("phone", "0911000001")).expectStatus(403);

        admin.post("/api/admin/accounts/" + id + "/unlock", Map.of()).expectStatus(200);
        // Phiên cũ đã bị huỷ lúc khoá, mở khoá không tự khôi phục
        user.get("/api/me").expectStatus(401);
        user.post("/api/auth/login", body("phone", "0911000001")).expectStatus(200);
        user.get("/api/me").expectStatus(200);
    }

    @Test
    @DisplayName("không tự khoá tài khoản đang đăng nhập")
    void khongTuKhoa() {
        var admin = admin();
        String me = idOfRole("ADMIN");
        admin.post("/api/admin/accounts/" + me + "/lock", Map.of()).expectStatus(400);
        assertThat(column("is_active", me)).isEqualTo("1");
    }

    // ---------- Đổi vai trò và đặt lại mật khẩu (BR.32, UC.8.5) ----------

    @Test
    @DisplayName("lên vai trò nội bộ mà chưa có mật khẩu thì phải đặt mật khẩu tạm")
    void lenVaiTroCanMatKhauTam() {
        var admin = admin();
        client().registerUser();
        String id = idOf("0911000001");

        var res = admin.put("/api/admin/accounts/" + id + "/role", body("role", "RECEPTIONIST")).expectStatus(400);
        assertThat(errorCode(res)).isEqualTo("PASSWORD_REQUIRED");
        assertThat(column("role", id)).isEqualTo("USER");

        var noEmail = admin.put("/api/admin/accounts/" + id + "/role", body("role", "RECEPTIONIST", "tempPassword", TEMP)).expectStatus(400);
        assertThat(errorCode(noEmail)).isEqualTo("EMAIL_REQUIRED");
        var taken = admin.put("/api/admin/accounts/" + id + "/role",
                body("role", "RECEPTIONIST", "tempPassword", TEMP, "email", "admin@chuctu.vn")).expectStatus(409);
        assertThat(errorCode(taken)).isEqualTo("EMAIL_TAKEN");
        assertThat(column("role", id)).isEqualTo("USER");

        var account = admin.put("/api/admin/accounts/" + id + "/role",
                body("role", "RECEPTIONIST", "tempPassword", TEMP, "email", "LenVaiTro@chuctu.vn")).expectStatus(200).json().get("account");
        assertThat(account.get("role").stringValue()).isEqualTo("RECEPTIONIST");
        assertThat(account.get("email").stringValue()).isEqualTo("lenvaitro@chuctu.vn");
        assertThat(column("must_change_password", id)).isEqualTo("1");
        staffLogin("0911000001", TEMP).expectStatus(200);
    }

    @Test
    @DisplayName("đổi vai trò sang Bác sĩ, Kỹ thuật viên và ngược lại; tạo thẳng tài khoản Bác sĩ, Kỹ thuật viên")
    void doiVaiTroBacSiKyThuatVien() {
        var admin = admin();
        String receptionist = idOfRole("RECEPTIONIST");
        for (String role : List.of("DOCTOR", "TECHNICIAN", "RECEPTIONIST", "DOCTOR")) {
            var account = admin.put("/api/admin/accounts/" + receptionist + "/role", body("role", role)).expectStatus(200).json().get("account");
            assertThat(account.get("role").stringValue()).isEqualTo(role);
            assertThat(column("role", receptionist)).isEqualTo(role);
        }
        // Người dùng lên Kỹ thuật viên: như mọi vai trò nội bộ, cần mật khẩu tạm và email
        client().registerUser();
        String user = idOf("0911000001");
        admin.put("/api/admin/accounts/" + user + "/role", body("role", "TECHNICIAN", "tempPassword", TEMP, "email", "ktv2@chuctu.vn"))
                .expectStatus(200);
        assertThat(column("role", user)).isEqualTo("TECHNICIAN");
        staffLogin("0911000001", TEMP).expectStatus(200);

        var created = admin.post("/api/admin/accounts", newAccount("role", "DOCTOR", "phone", "0920000009", "email", "bs2@chuctu.vn",
                "tempPassword", TEMP)).expectStatus(201).json().get("account");
        assertThat(created.get("role").stringValue()).isEqualTo("DOCTOR");
        JsonNode counts = admin.get("/api/admin/accounts").expectStatus(200).json().get("roleCounts");
        assertThat(counts.get("DOCTOR").intValue()).isEqualTo(2);
        assertThat(counts.get("TECHNICIAN").intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("đã có mật khẩu nhưng chưa có email thì đổi vai trò chỉ cần nhập email")
    void daCoMatKhauChuaCoEmail() {
        var admin = admin();
        String receptionist = idOfRole("RECEPTIONIST");
        jdbc.update("UPDATE users SET email = NULL WHERE id = ?", Long.parseLong(receptionist));
        var res = admin.put("/api/admin/accounts/" + receptionist + "/role", body("role", "ADMIN")).expectStatus(400);
        assertThat(errorCode(res)).isEqualTo("EMAIL_REQUIRED");
        admin.put("/api/admin/accounts/" + receptionist + "/role", body("role", "ADMIN", "email", "moi@chuctu.vn")).expectStatus(200);
    }

    @Test
    @DisplayName("đã có mật khẩu thì đổi vai trò không cần mật khẩu tạm")
    void daCoMatKhauKhongCanTam() {
        var admin = admin();
        String receptionist = idOfRole("RECEPTIONIST");
        admin.put("/api/admin/accounts/" + receptionist + "/role", body("role", "ADMIN")).expectStatus(200);
        assertThat(column("role", receptionist)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Admin không tự hạ quyền chính mình, vai trò lạ bị từ chối")
    void khongTuHaQuyen() {
        var admin = admin();
        String me = idOfRole("ADMIN");
        admin.put("/api/admin/accounts/" + me + "/role", body("role", "RECEPTIONIST")).expectStatus(400);
        admin.put("/api/admin/accounts/" + me + "/role", body("role", "THANH_VIEN")).expectStatus(400);
        assertThat(column("role", me)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("đặt lại mật khẩu: mật khẩu cũ hết tác dụng, phải đổi khi đăng nhập")
    void datLaiMatKhau() {
        var admin = admin();
        String receptionist = idOfRole("RECEPTIONIST");
        admin.post("/api/admin/accounts/" + receptionist + "/reset-password", body("tempPassword", TEMP)).expectStatus(200);

        staffLogin("letan@chuctu.vn", "ChucTu@2026").expectStatus(401);
        staffLogin("letan@chuctu.vn", TEMP).expectStatus(200);
        assertThat(column("must_change_password", receptionist)).isEqualTo("1");
    }

    @Test
    @DisplayName("đặt lại mật khẩu thì phiên cũ của người đó không làm việc được nữa")
    void datLaiMatKhauPhienCu() {
        var admin = admin();
        var staff = client().staff();
        staff.get("/api/staff/queue").expectStatus(200);
        admin.post("/api/admin/accounts/" + idOfRole("RECEPTIONIST") + "/reset-password", body("tempPassword", TEMP)).expectStatus(200);
        staff.get("/api/staff/queue").expectStatus(403);
    }

    @Test
    @DisplayName("không tự đặt lại mật khẩu, tiếp tân không gọi được, mật khẩu tạm phải đủ dài")
    void datLaiMatKhauRangBuoc() {
        var admin = admin();
        var staff = client().staff();
        String me = idOfRole("ADMIN");
        String receptionist = idOfRole("RECEPTIONIST");
        admin.post("/api/admin/accounts/" + me + "/reset-password", body("tempPassword", TEMP)).expectStatus(400);
        admin.post("/api/admin/accounts/" + receptionist + "/reset-password", body("tempPassword", "ngan")).expectStatus(400);
        staff.post("/api/admin/accounts/" + me + "/reset-password", body("tempPassword", TEMP)).expectStatus(403);
    }

    @Test
    @DisplayName("tài khoản người dùng không có mật khẩu để đặt lại")
    void nguoiDungKhongCoMatKhau() {
        var admin = admin();
        client().registerUser();
        admin.post("/api/admin/accounts/" + idOf("0911000001") + "/reset-password", body("tempPassword", TEMP)).expectStatus(400);
    }

    // ---------- [java] ----------

    @Test
    @DisplayName("[java] đổi vai trò có hiệu lực ở yêu cầu kế tiếp của người đó")
    void javaDoiVaiTroCoHieuLucNgay() {
        var admin = admin();
        var receptionist = client().staff();
        receptionist.get("/api/staff/queue").expectStatus(200);
        admin.put("/api/admin/accounts/" + idOfRole("RECEPTIONIST") + "/role", body("role", "USER")).expectStatus(200);
        receptionist.get("/api/staff/queue").expectStatus(403);
        receptionist.get("/api/me").expectStatus(200);
    }

    @Test
    @DisplayName("[java] email dài quá 700 ký tự báo VALIDATION ở ô email, đúng 700 thì nhận")
    void javaEmailQuaDai() {
        var admin = admin();
        String atLimit = "a".repeat(700 - "@chuctu.vn".length()) + "@chuctu.vn";
        String tooLong = "a".repeat(701 - "@chuctu.vn".length()) + "@chuctu.vn";
        assertThat(atLimit).hasSize(700);

        var create = admin.post("/api/admin/accounts", newAccount("email", tooLong)).expectStatus(400);
        assertThat(errorCode(create)).isEqualTo("VALIDATION");
        assertThat(fields(create).get("email").stringValue()).isNotEmpty();

        String receptionist = idOfRole("RECEPTIONIST");
        var update = admin.patch("/api/admin/accounts/" + receptionist, body("email", tooLong)).expectStatus(400);
        assertThat(errorCode(update)).isEqualTo("VALIDATION");
        assertThat(fields(update).has("email")).isTrue();
        assertThat(column("email", receptionist)).isEqualTo("letan@chuctu.vn");

        client().registerUser();
        var role = admin.put("/api/admin/accounts/" + idOf("0911000001") + "/role",
                body("role", "RECEPTIONIST", "tempPassword", TEMP, "email", tooLong)).expectStatus(400);
        assertThat(fields(role).has("email")).isTrue();

        // Quên mật khẩu chỉ đọc email nên giữ cách của Node: trả lời như thường, không báo lỗi độ dài
        client().post("/api/auth/staff/forgot-password", body("email", tooLong)).expectStatus(200);
        var change = client().staff().post("/api/staff/account/email", body("email", tooLong)).expectStatus(400);
        assertThat(fields(change).has("email")).isTrue();

        admin.post("/api/admin/accounts", newAccount("email", atLimit)).expectStatus(201);
        assertThat(column("email", idOf("0920000001"))).isEqualTo(atLimit);
    }

    @Test
    @DisplayName("[java] sửa tài khoản giữ nguyên các ô không gửi lên")
    void javaSuaChiGhiCotDaDoi() {
        var admin = admin();
        var account = admin.post("/api/admin/accounts", newAccount()).expectStatus(201).json().get("account");
        String id = account.get("id").stringValue();
        jdbc.update("UPDATE users SET address = 'Địa chỉ mới', last_login_at = UTC_TIMESTAMP(3) WHERE id = ?", Long.parseLong(id));

        admin.patch("/api/admin/accounts/" + id, body("fullName", "Tên Sau Khi Sửa")).expectStatus(200);
        assertThat(column("full_name", id)).isEqualTo("Tên Sau Khi Sửa");
        assertThat(column("address", id)).isEqualTo("Địa chỉ mới");
        assertThat(column("last_login_at", id)).isNotNull();
    }
}
