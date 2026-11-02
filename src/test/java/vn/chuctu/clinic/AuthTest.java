package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.auth.AuthService;
import vn.chuctu.clinic.auth.OtpService;
import vn.chuctu.clinic.auth.OtpService.Proof;
import vn.chuctu.clinic.auth.SmsGateway;
import vn.chuctu.clinic.auth.TrustedDeviceRepository;
import vn.chuctu.clinic.auth.VerificationCode;
import vn.chuctu.clinic.auth.VerificationCodeRepository;
import vn.chuctu.clinic.common.ApiException;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.common.Hmac;
import vn.chuctu.clinic.patient.PatientService;

class AuthTest extends IntegrationTest {

    @Autowired VerificationCodeRepository codes;
    @Autowired TrustedDeviceRepository devices;
    @Autowired OtpService otp;
    @Autowired AuthService auth;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper mapper;
    @MockitoSpyBean SmsGateway sms;
    @MockitoSpyBean PatientService patientService;

    // ---------- Hàm giúp ----------

    private static Map<String, Object> registration(String phone) {
        return Map.of("fullName", "A B", "phone", phone, "dob", "1970-01-01", "gender", "NU");
    }

    private static String errorCode(TestClient.Result res) {
        return res.json().get("error").get("code").stringValue();
    }

    private static String errorMessage(TestClient.Result res) {
        return res.json().get("error").get("message").stringValue();
    }

    private static JsonNode fields(TestClient.Result res) {
        return res.json().get("error").get("fields");
    }

    private static List<String> setCookies(TestClient.Result res) {
        return res.headers().allValues("set-cookie");
    }

    /** Giả lập đã qua 61 giây kể từ mọi lần xin mã của số này. */
    private void backdate(String phone) {
        for (VerificationCode c : codes.findAll()) {
            if (!c.getPhone().equals(phone)) continue;
            c.setCreatedAt(c.getCreatedAt().minusSeconds(61));
            codes.save(c);
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private User userWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setFullName("Người có sẵn");
        return users.save(user);
    }

    private String tokenOf(User user) {
        var response = new MockHttpServletResponse();
        sessions.setSession(response, user, true);
        String pair = response.getHeader("Set-Cookie").split(";")[0];
        return pair.substring(pair.indexOf('=') + 1);
    }

    private User staffByEmail(String email) {
        return users.findByEmail(email).orElseThrow();
    }

    // ---------- Đăng ký và đăng nhập người dùng (auth.test.js) ----------

    @Test
    @Disabled("Task 6")
    @DisplayName("đăng ký bằng mã 6 số tạo tài khoản, hồ sơ chính chủ và đăng nhập luôn (BR.02)")
    void dangKyBangMa() {
        var agent = client().registerUser("0911000001", "Nguyễn Văn A");
        var me = agent.get("/api/me").expectStatus(200).json();
        assertThat(me.get("user").get("phone").stringValue()).isEqualTo("0911000001");
        assertThat(me.get("patientCode").stringValue()).matches("^CT-BN-\\d{2}-\\d{4}$");
    }

    @Test
    @DisplayName("mỗi số điện thoại chỉ một tài khoản (BR.01)")
    void moiSoMotTaiKhoan() {
        client().registerUser("0911000001", "Nguyễn Văn A");
        var res = client().post("/api/auth/register", registration("0911000001")).expectStatus(409);
        assertThat(errorCode(res)).isEqualTo("PHONE_TAKEN");
    }

    @Test
    @DisplayName("báo lỗi rõ ràng theo từng trường khi số điện thoại sai định dạng")
    void baoLoiTheoTruong() {
        var res = client().post("/api/auth/register", registration("12345")).expectStatus(400);
        assertThat(fields(res).get("phone").stringValue()).contains("thiếu 4 chữ số");
    }

    @Test
    @DisplayName("mã sai thì không tạo tài khoản và báo số lần còn lại")
    void maSaiKhongTaoTaiKhoan() {
        var agent = client();
        var res = agent.post("/api/auth/register", registration("0911000002")).expectStatus(200);
        String devCode = res.json().get("devCode").stringValue();
        String wrong = devCode.equals("000000") ? "111111" : "000000";
        var fail = agent.post("/api/auth/register/verify", Map.of("phone", "0911000002", "code", wrong)).expectStatus(400);
        assertThat(errorCode(fail)).isEqualTo("CODE_WRONG");
        assertThat(errorMessage(fail)).isEqualTo("Mã xác nhận chưa đúng. Cô chú còn 4 lần nhập.");
        agent.get("/api/me").expectStatus(401);
    }

    @Test
    @DisplayName("gửi lại mã phải chờ 60 giây và tối đa 3 mã mỗi giờ (BR.03)")
    void guiLaiMa() {
        String phone = "0911000003";
        client().post("/api/auth/register", registration(phone)).expectStatus(200);
        var resend = Map.of("phone", phone, "purpose", "REGISTER");
        var tooSoon = client().post("/api/auth/otp/resend", resend).expectStatus(429);
        assertThat(errorMessage(tooSoon)).contains("chờ");

        // Giả lập đã qua 60 giây giữa các lần gửi
        backdate(phone);
        client().post("/api/auth/otp/resend", resend).expectStatus(200);
        backdate(phone);
        client().post("/api/auth/otp/resend", resend).expectStatus(200);
        backdate(phone);
        var limited = client().post("/api/auth/otp/resend", resend).expectStatus(429);
        assertThat(errorMessage(limited)).contains("3 mã");
    }

    @Test
    @DisplayName("thiết bị đã xác nhận đăng nhập ngay, thiết bị mới phải nhập mã (BR.02)")
    void thietBiDaXacNhan() {
        var agent = client().registerUser("0911000004", "Nguyễn Văn A");
        agent.post("/api/auth/logout", Map.of()).expectStatus(200);
        var sameDevice = agent.post("/api/auth/login", Map.of("phone", "0911000004")).expectStatus(200);
        assertThat(sameDevice.json().get("needCode").booleanValue()).isFalse();

        var newDevice = client();
        var res = newDevice.post("/api/auth/login", Map.of("phone", "0911000004")).expectStatus(200);
        assertThat(res.json().get("needCode").booleanValue()).isTrue();
        newDevice.post("/api/auth/login/verify", Map.of("phone", "0911000004", "code", res.json().get("devCode").stringValue()))
                .expectStatus(200);
        // Node đọc /api/me (Task 6); /api/auth/me cũng cho thấy phiên đã được thiết lập
        var me = newDevice.get("/api/auth/me").expectStatus(200).json();
        assertThat(me.get("user").get("phone").stringValue()).isEqualTo("0911000004");
    }

    @Test
    @DisplayName("người dùng không có quyền vào khu vực tiếp tân (QAs1)")
    void nguoiDungKhongVaoKhuTiepTan() {
        var agent = client().registerUser("0911000005", "Nguyễn Văn A");
        // Một phiên chung: đã đăng nhập nhưng vai trò USER thì bị từ chối quyền
        agent.get("/api/staff/patients").expectStatus(403);
        agent.get("/api/admin/accounts").expectStatus(403);
    }

    // ---------- Tài khoản nội bộ ----------

    @Test
    @DisplayName("tiếp tân đăng nhập bằng email hoặc số điện thoại và mật khẩu (BR.06)")
    void tiepTanDangNhap() {
        client().staff("letan@chuctu.vn");
        client().staff("0900000002");
        client().post("/api/auth/staff/login", Map.of("identifier", "letan@chuctu.vn", "password", "sai-mat-khau")).expectStatus(401);
    }

    @Test
    @DisplayName("tiếp tân không dùng được chức năng của Admin (BR.05)")
    void tiepTanKhongDungChucNangAdmin() {
        var agent = client().staff();
        // Node gọi /api/staff/queue (Task 8) và /api/admin/dashboard (Task 11); đường dẫn thử cho cùng phân quyền
        agent.get("/api/staff/_probe").expectStatus(200);
        agent.get("/api/admin/_probe").expectStatus(403);
    }

    @Test
    @Disabled("Task 11")
    @DisplayName("tài khoản bị khoá không đăng nhập được và phiên cũ hết hiệu lực ngay (BR.33)")
    void taiKhoanBiKhoa() {
        var admin = client().staff("admin@chuctu.vn");
        var user = client().registerUser("0911000006", "Nguyễn Văn A");
        var list = admin.get("/api/admin/accounts?q=0911000006").expectStatus(200).json();
        admin.post("/api/admin/accounts/" + list.get("items").get(0).get("id").stringValue() + "/lock", Map.of()).expectStatus(200);
        user.get("/api/me").expectStatus(401);
        var res = user.post("/api/auth/login", Map.of("phone", "0911000006")).expectStatus(403);
        assertThat(errorMessage(res)).contains("khoá");
    }

    @Test
    @Disabled("Task 11")
    @DisplayName("tài khoản nội bộ mới phải đổi mật khẩu tạm trước khi làm việc")
    void taiKhoanNoiBoMoiDoiMatKhauTam() {
        var admin = client().staff("admin@chuctu.vn");
        admin.post("/api/admin/accounts", Map.of("fullName", "Lễ Tân Mới", "phone", "0900000099", "email", "letanmoi@chuctu.vn",
                "role", "RECEPTIONIST", "tempPassword", "TamThoi123")).expectStatus(201);
        var agent = client();
        agent.post("/api/auth/staff/login", Map.of("identifier", "0900000099", "password", "TamThoi123")).expectStatus(200);
        agent.get("/api/staff/queue").expectStatus(403);
        agent.post("/api/auth/change-password", Map.of("currentPassword", "TamThoi123", "newPassword", "MatKhauMoi456")).expectStatus(200);
        agent.get("/api/staff/queue").expectStatus(200);
    }

    @Test
    @DisplayName("admin và tiếp tân tự đổi mật khẩu, mật khẩu cũ hết dùng được")
    void tuDoiMatKhau() {
        for (String identifier : List.of("admin@chuctu.vn", "letan@chuctu.vn")) {
            var agent = client().staff(identifier);
            agent.post("/api/auth/change-password", Map.of("currentPassword", "ChucTu@2026", "newPassword", "MatKhauMoi456"))
                    .expectStatus(200);
            client().post("/api/auth/staff/login", Map.of("identifier", identifier, "password", "ChucTu@2026")).expectStatus(401);
            client().post("/api/auth/staff/login", Map.of("identifier", identifier, "password", "MatKhauMoi456")).expectStatus(200);
        }
    }

    @Test
    @DisplayName("mật khẩu mới phải từ 8 ký tự")
    void matKhauMoiTu8KyTu() {
        var agent = client().staff();
        var res = agent.post("/api/auth/change-password", Map.of("currentPassword", "ChucTu@2026", "newPassword", "1234567"))
                .expectStatus(400);
        assertThat(fields(res).get("newPassword").stringValue()).contains("8 ký tự");
        agent.post("/api/auth/change-password", Map.of("currentPassword", "ChucTu@2026", "newPassword", "12345678")).expectStatus(200);
    }

    @Test
    @DisplayName("đổi mật khẩu cần đúng mật khẩu hiện tại và mật khẩu mới phải khác")
    void doiMatKhauCanDungMatKhauHienTai() {
        var agent = client().staff();
        var res = agent.post("/api/auth/change-password", Map.of("currentPassword", "sai-mat-khau", "newPassword", "MatKhauMoi456"))
                .expectStatus(400);
        assertThat(fields(res).get("currentPassword").stringValue()).isNotEmpty();
        res = agent.post("/api/auth/change-password", Map.of("currentPassword", "ChucTu@2026", "newPassword", "ChucTu@2026"))
                .expectStatus(400);
        assertThat(fields(res).get("newPassword").stringValue()).isNotEmpty();
    }

    @Test
    @DisplayName("người dùng và khách chưa đăng nhập không đổi mật khẩu được")
    void nguoiDungKhongDoiMatKhau() {
        client().post("/api/auth/change-password", Map.of("currentPassword", "x", "newPassword", "MatKhauMoi456")).expectStatus(401);
        var user = client().registerUser("0911000007", "Nguyễn Văn A");
        user.post("/api/auth/change-password", Map.of("currentPassword", "x", "newPassword", "MatKhauMoi456")).expectStatus(400);
    }

    @Test
    @Disabled("Task 4")
    @DisplayName("admin và tiếp tân tự sửa họ tên, không sửa được số điện thoại, email, vai trò")
    void tuSuaHoTen() {
        for (String identifier : List.of("admin@chuctu.vn", "letan@chuctu.vn")) {
            var agent = client().staff(identifier);
            var before = agent.get("/api/auth/me").expectStatus(200).json().get("user");
            var res = agent.patch("/api/staff/account", "{\"fullName\":\"  Tên Mới Sửa  \",\"phone\":\"0999999999\","
                    + "\"email\":\"khac@chuctu.vn\",\"role\":\"USER\"}").expectStatus(200).json().get("user");
            assertThat(res.get("fullName").stringValue()).isEqualTo("Tên Mới Sửa");
            assertThat(res.get("phone")).isEqualTo(before.get("phone"));
            assertThat(res.get("email")).isEqualTo(before.get("email"));
            assertThat(res.get("role")).isEqualTo(before.get("role"));
        }
    }

    @Test
    @Disabled("Task 4")
    @DisplayName("họ tên để trống thì báo lỗi theo trường")
    void hoTenTrong() {
        var agent = client().staff();
        var res = agent.patch("/api/staff/account", "{\"fullName\":\"   \"}").expectStatus(400);
        assertThat(fields(res).get("fullName").stringValue()).isNotEmpty();
    }

    @Test
    @DisplayName("người dùng không sửa được qua API nội bộ")
    void nguoiDungKhongSuaQuaApiNoiBo() {
        var user = client().registerUser("0911000008", "Nguyễn Văn A");
        user.patch("/api/staff/account", "{\"fullName\":\"Ai Đó\"}").expectStatus(403);
    }

    @Test
    @Disabled("Task 11")
    @DisplayName("admin sửa tên hoặc số điện thoại ở Quản lý tài khoản thì email giữ nguyên")
    void adminSuaTenGiuEmail() {
        var admin = client().staff("admin@chuctu.vn");
        var me = admin.get("/api/auth/me").expectStatus(200).json().get("user");
        var res = admin.patch("/api/admin/accounts/" + me.get("id").stringValue(),
                "{\"fullName\":\"Admin\",\"phone\":\"" + me.get("phone").stringValue() + "\"}").expectStatus(200).json();
        assertThat(res.get("account").get("fullName").stringValue()).isEqualTo("Admin");
        assertThat(res.get("account").get("email").stringValue()).isEqualTo("admin@chuctu.vn");
        // Tài khoản nội bộ không xoá được email
        var cleared = admin.patch("/api/admin/accounts/" + me.get("id").stringValue(), "{\"email\":\"\"}").expectStatus(400);
        assertThat(errorCode(cleared)).isEqualTo("EMAIL_REQUIRED");
    }

    @Test
    @Disabled("Task 11")
    @DisplayName("mật khẩu tạm phải từ 8 ký tự")
    void matKhauTamTu8KyTu() {
        var admin = client().staff("admin@chuctu.vn");
        var res = admin.post("/api/admin/accounts", Map.of("fullName", "Lễ Tân", "phone", "0900000098", "email", "letan98@chuctu.vn",
                "role", "RECEPTIONIST", "tempPassword", "123")).expectStatus(400);
        assertThat(fields(res).get("tempPassword").stringValue()).isNotEmpty();
    }

    // ---------- Một phiên đăng nhập cho cả hai khu ----------

    @Test
    @DisplayName("đăng nhập nội bộ thay phiên khách")
    void dangNhapNoiBoThayPhienKhach() {
        var agent = client().registerUser("0911000099", "Người dùng một phiên");
        // Node gọi /api/me/patients (Task 6) và /api/staff/queue (Task 8); đường dẫn thử cho cùng phân quyền
        agent.get("/api/me/_probe").expectStatus(200);
        agent.post("/api/auth/staff/login", Map.of("identifier", "letan@chuctu.vn", "password", "ChucTu@2026")).expectStatus(200);
        var me = agent.get("/api/auth/me").expectStatus(200).json();
        assertThat(me.get("user").get("role").stringValue()).isEqualTo("RECEPTIONIST");
        agent.get("/api/me/_probe").expectStatus(403);
        agent.get("/api/staff/_probe").expectStatus(200);
    }

    @Test
    @DisplayName("đăng xuất thoát phiên duy nhất")
    void dangXuat() {
        var agent = client().staff();
        agent.post("/api/auth/logout", Map.of()).expectStatus(200);
        agent.get("/api/staff/queue").expectStatus(401);
        var me = agent.get("/api/auth/me").expectStatus(200).json();
        assertThat(me.get("user").isNull()).isTrue();
    }

    @Test
    @DisplayName("X-App bị bỏ qua")
    void xAppBiBoQua() {
        var agent = client().staff();
        var me = agent.header("X-App", "users").get("/api/auth/me").expectStatus(200).json();
        assertThat(me.get("user").get("role").stringValue()).isEqualTo("RECEPTIONIST");
    }

    @Test
    @DisplayName("ct_session hỏng thì xoá cả hai, không dùng ct_staff")
    void ctSessionHongKhongDungCtStaff() {
        // Bản Java bỏ hẳn ct_staff (xem SessionTest): chỉ ct_session bị xoá, ct_staff không bao giờ được dùng
        var res = client().cookie("ct_session", "rac").cookie("ct_staff", tokenOf(staffByEmail("letan@chuctu.vn")))
                .get("/api/staff/_probe").expectStatus(401);
        assertThat(setCookies(res)).anySatisfy(c -> assertThat(c).startsWith("ct_session=;").contains("Max-Age=0"));
    }

    // ---------- Nhiều tab trên cùng trình duyệt ----------

    @Test
    @DisplayName("từ chối thao tác của tab còn hiển thị tài khoản cũ sau khi tab khác đăng nhập tài khoản khác")
    void tuChoiTabCu() {
        var agent = client();
        var a = agent.post("/api/auth/staff/login", Map.of("identifier", "letan@chuctu.vn", "password", "ChucTu@2026")).expectStatus(200);
        String idA = a.json().get("user").get("id").stringValue();
        agent.header("X-Session-User", idA).get("/api/staff/_probe").expectStatus(200);

        // Tab khác (cùng cookie) đăng nhập bằng tài khoản admin
        var b = agent.post("/api/auth/staff/login", Map.of("identifier", "admin@chuctu.vn", "password", "ChucTu@2026")).expectStatus(200);
        String idB = b.json().get("user").get("id").stringValue();
        assertThat(idB).isNotEqualTo(idA);

        var res = agent.get("/api/staff/_probe").expectStatus(409);
        assertThat(errorCode(res)).isEqualTo("SESSION_CHANGED");
        // Tab vẫn xem được phiên hiện tại để tự tải lại
        var me = agent.get("/api/auth/me").expectStatus(200).json();
        assertThat(me.get("user").get("id").stringValue()).isEqualTo(idB);
        agent.header("X-Session-User", idB).get("/api/staff/_probe").expectStatus(200);
    }

    @Test
    @DisplayName("từ chối thao tác của tab khác sau khi đã đăng xuất")
    void tuChoiTabSauDangXuat() {
        var agent = client().staff();
        var me = agent.get("/api/auth/me").expectStatus(200).json();
        agent.post("/api/auth/logout", Map.of()).expectStatus(200);
        var res = agent.header("X-Session-User", me.get("user").get("id").stringValue()).get("/api/staff/_probe").expectStatus(409);
        assertThat(errorCode(res)).isEqualTo("SESSION_CHANGED");
    }

    // ---------- Cập nhật thông tin tài khoản ----------

    @Test
    @Disabled("Task 6")
    @DisplayName("ngày sinh, giới tính, địa chỉ là bắt buộc, không xoá trắng được")
    void thongTinBatBuoc() {
        var agent = client().registerUser("0911000055", "Nguyễn Văn A");
        var res = agent.patch("/api/me", "{\"dob\":null,\"gender\":null,\"address\":\"\"}").expectStatus(400);
        assertThat(fields(res).get("dob").stringValue()).contains("ngày, tháng, năm sinh");
        assertThat(fields(res).get("gender").stringValue()).contains("giới tính");
        assertThat(fields(res).get("address").stringValue()).contains("địa chỉ");
        var ok = agent.patch("/api/me", "{\"dob\":\"1955-04-12\",\"gender\":\"NU\","
                + "\"address\":\"12 Nguyễn Văn Đậu, Phường 5, Quận Phú Nhuận, TP. Hồ Chí Minh\"}").expectStatus(200).json();
        assertThat(ok.get("user").get("gender").stringValue()).isEqualTo("NU");
    }

    // ---------- Thêm cho bản Java ----------

    @Test
    @DisplayName("[java] đăng ký tạo tài khoản, hồ sơ chính chủ, thiết bị tin cậy và cookie phiên")
    void dangKyTaoTaiKhoanVaHoSo() {
        var agent = client();
        var start = agent.post("/api/auth/register", Map.of("fullName", "Nguyễn Văn A", "phone", "0911000001",
                "dob", "1960-01-01", "gender", "NAM", "address", "Bình Thạnh")).expectStatus(200).json();
        assertThat(start.get("sent").booleanValue()).isTrue();
        assertThat(start.get("provider").stringValue()).isEqualTo("server");
        assertThat(start.get("expiresInSeconds").intValue()).isEqualTo(300);
        assertThat(start.get("resendAfterSeconds").intValue()).isEqualTo(60);
        assertThat(start.get("devCode").stringValue()).matches("^\\d{6}$");
        assertThat(count("users")).isEqualTo(2);

        var done = agent.post("/api/auth/register/verify", Map.of("phone", "0911000001", "code", start.get("devCode").stringValue()))
                .expectStatus(201);
        JsonNode user = done.json().get("user");
        assertThat(user.get("phone").stringValue()).isEqualTo("0911000001");
        assertThat(user.get("role").stringValue()).isEqualTo("USER");
        assertThat(user.get("dob").stringValue()).isEqualTo("1960-01-01T00:00:00.000Z");
        assertThat(user.get("createdAt").stringValue()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(user.get("lastLoginAt").isNull()).isFalse();
        assertThat(user.get("infoUpdatedAt")).isEqualTo(user.get("createdAt"));
        assertThat(user.get("isActive").booleanValue()).isTrue();
        assertThat(user.get("mustChangePassword").booleanValue()).isFalse();
        assertThat(user.has("passwordHash")).isFalse();

        assertThat(setCookies(done)).anySatisfy(c -> assertThat(c).startsWith("ct_session=").contains("Max-Age=2592000", "HttpOnly"))
                .anySatisfy(c -> assertThat(c).startsWith("ct_device=").contains("Max-Age=31536000"));
        assertThat(agent.get("/api/auth/me").expectStatus(200).json().get("user").get("id")).isEqualTo(user.get("id"));

        // Hồ sơ chính chủ tạo sẵn, mã CT-BN-YY-0001
        Map<String, Object> patient = jdbc.queryForMap("SELECT * FROM patients");
        assertThat(patient.get("patient_code").toString()).matches("^CT-BN-\\d{2}-0001$");
        assertThat(patient.get("relationship")).isEqualTo("BAN_THAN");
        assertThat(patient.get("user_id").toString()).isEqualTo(user.get("id").stringValue());
        assertThat(patient.get("created_by").toString()).isEqualTo(user.get("id").stringValue());
        assertThat(patient.get("full_name")).isEqualTo("Nguyễn Văn A");
        assertThat(patient.get("birth_year")).isEqualTo(1960);
        assertThat(patient.get("phone")).isEqualTo("0911000001");
        assertThat(patient.get("address")).isEqualTo("Bình Thạnh");
        assertThat(count("trusted_devices")).isEqualTo(1);
    }

    @Test
    @DisplayName("[java] đăng ký: lỗi từng ô theo thứ tự ô và thông báo như zod")
    void dangKyLoiTungO() {
        var res = client().post("/api/auth/register", "{}").expectStatus(400);
        assertThat(errorCode(res)).isEqualTo("VALIDATION");
        assertThat(fields(res).propertyNames()).containsExactly("fullName", "phone", "dob", "gender");
        assertThat(fields(res).get("fullName").stringValue()).isEqualTo("Cô chú vui lòng nhập họ và tên.");
        assertThat(fields(res).get("phone").stringValue()).isEqualTo("Cô chú vui lòng nhập số điện thoại.");
        assertThat(fields(res).get("dob").stringValue()).isEqualTo("Required");
        assertThat(fields(res).get("gender").stringValue()).isEqualTo("Cô chú vui lòng chọn giới tính.");

        String today = java.time.LocalDate.now(vn.chuctu.clinic.common.VnTime.ZONE).toString();
        var body = new java.util.LinkedHashMap<String, Object>(registration("0911000009"));
        body.put("dob", today);
        body.put("gender", "X");
        body.put("address", "a".repeat(301));
        res = client().post("/api/auth/register", body).expectStatus(400);
        assertThat(fields(res).get("dob").stringValue()).isEqualTo("Ngày sinh phải trước hôm nay.");
        assertThat(fields(res).get("gender").stringValue()).isEqualTo("Cô chú vui lòng chọn giới tính.");
        assertThat(fields(res).get("address").stringValue()).isEqualTo("Tối đa 300 ký tự.");

        body.put("dob", "2026-02-30");
        assertThat(fields(client().post("/api/auth/register", body).expectStatus(400)).get("dob").stringValue())
                .isEqualTo("Ngày không hợp lệ.");
        assertThat(count("verification_codes")).isZero();
    }

    @Test
    @DisplayName("[java] xác nhận mã: lỗi từng ô và thiếu mã")
    void xacNhanMaLoiTungO() {
        var res = client().post("/api/auth/login/verify", "{\"phone\":\"0911000001\",\"code\":\"12345\",\"idToken\":\"ngan\","
                + "\"remember\":\"yes\"}").expectStatus(400);
        assertThat(fields(res).get("code").stringValue()).isEqualTo("Mã xác nhận gồm 6 chữ số.");
        assertThat(fields(res).get("idToken").stringValue()).isEqualTo("String must contain at least 20 character(s)");
        assertThat(fields(res).get("remember").stringValue()).isEqualTo("Expected boolean, received string");

        res = client().post("/api/auth/login/verify", "{\"phone\":\"0911000001\",\"code\":null}").expectStatus(400);
        assertThat(fields(res).get("code").stringValue()).isEqualTo("Expected string, received null");

        // Không có mã (và không phải Firebase): CODE_REQUIRED, người dùng có tồn tại hay không đều như nhau
        res = client().post("/api/auth/register/verify", Map.of("phone", "0911000001")).expectStatus(400);
        assertThat(errorCode(res)).isEqualTo("CODE_REQUIRED");
        assertThat(fields(res).get("code").stringValue()).isEqualTo("Mã xác nhận gồm 6 chữ số.");
    }

    @Test
    @DisplayName("[java] mã sai được ghi lại dù request trả lỗi, đủ 5 lần thì khoá")
    void maSaiDuocGhiLai() {
        var agent = client();
        String devCode = agent.post("/api/auth/register", registration("0911000010")).expectStatus(200).json().get("devCode").stringValue();
        String wrong = devCode.equals("000000") ? "111111" : "000000";
        var verify = Map.of("phone", "0911000010", "code", wrong);

        agent.post("/api/auth/register/verify", verify).expectStatus(400);
        assertThat(codes.findAll().get(0).getAttempts()).isEqualTo(1);
        for (int left = 3; left >= 1; left--) {
            var res = agent.post("/api/auth/register/verify", verify).expectStatus(400);
            assertThat(errorMessage(res)).isEqualTo("Mã xác nhận chưa đúng. Cô chú còn " + left + " lần nhập.");
        }
        var last = agent.post("/api/auth/register/verify", verify).expectStatus(400);
        assertThat(errorMessage(last)).isEqualTo("Mã xác nhận chưa đúng. Cô chú bấm \"Gửi lại mã\" để nhận mã mới.");
        assertThat(codes.findAll().get(0).getAttempts()).isEqualTo(5);

        // Khoá: ngay cả mã đúng cũng bị từ chối
        var locked = agent.post("/api/auth/register/verify", Map.of("phone", "0911000010", "code", devCode)).expectStatus(400);
        assertThat(errorCode(locked)).isEqualTo("CODE_LOCKED");
        assertThat(count("users")).isEqualTo(2);
    }

    @Test
    @DisplayName("[java] mã sai vẫn được ghi lại khi nằm trong giao dịch bị hoàn tác")
    void maSaiGhiLaiTrongGiaoDichBiHoanTac() {
        String phone = "0911000011";
        String devCode = otp.issue(phone, "NEW_DEVICE", null, null).get("devCode").toString();
        String wrong = devCode.equals("000000") ? "111111" : "000000";

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> otp.verify(phone, "NEW_DEVICE", wrong)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CODE_WRONG"));
        assertThat(codes.findAll().get(0).getAttempts()).isEqualTo(1);

        // Giao dịch tự đánh dấu hoàn tác sau khi bắt lỗi cũng không làm mất lần sai
        tx.executeWithoutResult(status -> {
            assertThatThrownBy(() -> otp.verify(phone, "NEW_DEVICE", wrong)).isInstanceOf(ApiException.class);
            status.setRollbackOnly();
        });
        assertThat(codes.findAll().get(0).getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("[java] mã đúng chỉ dùng được một lần")
    void maDungChiDungMotLan() {
        String phone = "0911000012";
        String devCode = otp.issue(phone, "NEW_DEVICE", null, null).get("devCode").toString();
        VerificationCode used = otp.verify(phone, "NEW_DEVICE", devCode);
        assertThat(used.getUsedAt()).isNotNull();
        assertThat(codes.findAll().get(0).getUsedAt()).isNotNull();
        assertThatThrownBy(() -> otp.verify(phone, "NEW_DEVICE", devCode))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CODE_EXPIRED"));
    }

    @Test
    @DisplayName("[java] mã hết hạn sau 5 phút")
    void maHetHan() {
        String phone = "0911000013";
        String devCode = otp.issue(phone, "NEW_DEVICE", null, null).get("devCode").toString();
        clock.set(Instant.now().plus(5, ChronoUnit.MINUTES).plusSeconds(5));
        assertThatThrownBy(() -> otp.verify(phone, "NEW_DEVICE", devCode))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("CODE_EXPIRED");
                    assertThat(e.getMessage()).isEqualTo("Mã xác nhận đã hết hạn. Cô chú bấm \"Gửi lại mã\" để nhận mã mới.");
                });
    }

    @Test
    @DisplayName("[java] mã mới làm mã cũ cùng mục đích hết hiệu lực")
    void maMoiLamMaCuHetHieuLuc() {
        String phone = "0911000014";
        String oldCode = otp.issue(phone, "NEW_DEVICE", null, null).get("devCode").toString();
        backdate(phone);
        String newCode = otp.issue(phone, "NEW_DEVICE", null, null).get("devCode").toString();
        // Mã khác mục đích không bị ảnh hưởng, mã cũ cùng mục đích không còn mở
        long open = codes.findAll().stream().filter(c -> c.getExpiresAt().isAfter(Instant.now())).count();
        assertThat(open).isEqualTo(1);
        if (!oldCode.equals(newCode)) {
            assertThatThrownBy(() -> otp.verify(phone, "NEW_DEVICE", oldCode))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CODE_WRONG"));
        }
        assertThat(otp.verify(phone, "NEW_DEVICE", newCode).getUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("[java] 429 kèm retryAfter tính bằng giây")
    void retryAfter() {
        String phone = "0911000015";
        client().post("/api/auth/register", registration(phone)).expectStatus(200);
        var res = client().post("/api/auth/otp/resend", Map.of("phone", phone, "purpose", "REGISTER")).expectStatus(429);
        assertThat(errorCode(res)).isEqualTo("TOO_MANY_REQUESTS");
        int retryAfter = res.json().get("error").get("retryAfter").intValue();
        assertThat(retryAfter).isBetween(1, 60);
        assertThat(errorMessage(res)).isEqualTo("Cô chú vui lòng chờ " + retryAfter + " giây rồi bấm gửi lại mã.");
    }

    @Test
    @DisplayName("[java] mã và thiết bị được băm HMAC-SHA256 bằng JWT_SECRET đúng công thức")
    void congThucBam() {
        String phone = "0911000016";
        String devCode = otp.issue(phone, "NEW_DEVICE", null, null).get("devCode").toString();
        assertThat(codes.findAll().get(0).getCodeHash())
                .isEqualTo(Hmac.sha256Hex(config.jwtSecret, phone + ":NEW_DEVICE:" + devCode)).hasSize(64);

        User user = createUser("USER");
        auth.trustDevice(user.getId(), "thiet-bi-1");
        auth.trustDevice(user.getId(), "thiet-bi-1");
        assertThat(jdbc.queryForList("SELECT device_hash FROM trusted_devices", String.class))
                .containsExactly(Hmac.sha256Hex(config.jwtSecret, "device:thiet-bi-1"));
        assertThat(auth.newDeviceId()).matches("^[A-Za-z0-9_-]{43}$").isNotEqualTo(auth.newDeviceId());
    }

    @Test
    @DisplayName("[java] gửi mã lỗi thì xoá lần xin mã và không tính vào giới hạn")
    void guiMaLoiXoaLanXinMa() {
        doThrow(new IllegalStateException("cổng tin nhắn hỏng")).when(sms).sendVerificationCode(anyString(), anyString(), anyString());
        var res = client().post("/api/auth/register", registration("0911000017")).expectStatus(500);
        assertThat(errorCode(res)).isEqualTo("SERVER_ERROR");
        assertThat(count("verification_codes")).isZero();

        doCallRealMethod().when(sms).sendVerificationCode(any(), any(), any());
        client().post("/api/auth/register", registration("0911000017")).expectStatus(200);
        assertThat(count("verification_codes")).isEqualTo(1);
    }

    @Test
    @DisplayName("[java] SMS_PROVIDER=firebase: chỉ ghi nhận lần xin mã, không gửi và không lộ mã")
    void firebaseChiGhiNhanLanXinMa() {
        var firebaseConfig = new AppProperties(new MockEnvironment().withProperty("SMS_PROVIDER", "firebase"));
        var gateway = new SmsGateway(firebaseConfig);
        var firebaseOtp = new OtpService(codes, gateway, firebaseConfig, clock, mapper);

        Map<String, Object> out = firebaseOtp.issue("0911000018", "NEW_DEVICE", null, null);
        assertThat(out).containsEntry("provider", "firebase").containsEntry("sent", true).doesNotContainKey("devCode");
        assertThat(out.keySet()).containsExactly("sent", "provider", "expiresInSeconds", "resendAfterSeconds");
        assertThat(codes.findAll().get(0).getCodeHash()).isEqualTo("firebase");

        // Với Firebase, bằng chứng là ID token chứ không phải mã 6 số: chưa kiểm được cho tới Task 5
        assertThatThrownBy(() -> firebaseOtp.verifyProof("0911000018", "NEW_DEVICE", new Proof("123456", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("[java] gửi lại mã: chưa đăng ký, đã có tài khoản, số lạ, mục đích sai")
    void guiLaiMaCacTruongHop() {
        var none = client().post("/api/auth/otp/resend", Map.of("phone", "0911000019", "purpose", "REGISTER")).expectStatus(400);
        assertThat(errorMessage(none)).isEqualTo("Cô chú vui lòng điền lại thông tin đăng ký.");

        client().post("/api/auth/register", registration("0911000019")).expectStatus(200);
        userWithPhone("0911000019");
        var taken = client().post("/api/auth/otp/resend", Map.of("phone", "0911000019", "purpose", "REGISTER")).expectStatus(409);
        assertThat(errorCode(taken)).isEqualTo("PHONE_TAKEN");

        var unknown = client().post("/api/auth/otp/resend", Map.of("phone", "0911000020", "purpose", "NEW_DEVICE")).expectStatus(404);
        assertThat(errorCode(unknown)).isEqualTo("NOT_FOUND");

        var bad = client().post("/api/auth/otp/resend", Map.of("phone", "0911000020", "purpose", "X")).expectStatus(400);
        assertThat(fields(bad).get("purpose").stringValue())
                .isEqualTo("Invalid enum value. Expected 'REGISTER' | 'NEW_DEVICE', received 'X'");
        var missing = client().post("/api/auth/otp/resend", Map.of("phone", "0911000020")).expectStatus(400);
        assertThat(fields(missing).get("purpose").stringValue()).isEqualTo("Required");
    }

    @Test
    @DisplayName("[java] gửi lại mã đăng ký giữ nguyên thông tin đăng ký và dùng được mã mới")
    void guiLaiMaDangKy() {
        String phone = "0911000021";
        client().post("/api/auth/register", Map.of("fullName", "Trần Thị B", "phone", phone, "dob", "1950-05-05", "gender", "NU",
                "address", "Quận 1")).expectStatus(200);
        backdate(phone);
        var agent = client();
        String devCode = agent.post("/api/auth/otp/resend", Map.of("phone", phone, "purpose", "REGISTER")).expectStatus(200)
                .json().get("devCode").stringValue();
        var user = agent.post("/api/auth/register/verify", Map.of("phone", phone, "code", devCode)).expectStatus(201).json().get("user");
        assertThat(user.get("fullName").stringValue()).isEqualTo("Trần Thị B");
        assertThat(user.get("dob").stringValue()).isEqualTo("1950-05-05T00:00:00.000Z");
        assertThat(user.get("address").stringValue()).isEqualTo("Quận 1");
    }

    @Test
    @DisplayName("[java] số điện thoại bị chiếm trong lúc chờ mã thì 409 và không tạo gì")
    void soBiChiemTrongLucChoMa() {
        String phone = "0911000022";
        String devCode = client().post("/api/auth/register", registration(phone)).expectStatus(200).json().get("devCode").stringValue();
        userWithPhone(phone);
        var res = client().post("/api/auth/register/verify", Map.of("phone", phone, "code", devCode)).expectStatus(409);
        assertThat(errorCode(res)).isEqualTo("PHONE_TAKEN");
        assertThat(errorMessage(res)).isEqualTo("Số điện thoại này đã có tài khoản.");
        assertThat(res.json().get("error").has("fields")).isFalse();
        assertThat(count("patients")).isZero();
    }

    @Test
    @DisplayName("[java] tạo tài khoản và hồ sơ trong một giao dịch: hỏng hồ sơ thì không còn tài khoản")
    void taoTaiKhoanVaHoSoMotGiaoDich() {
        String phone = "0911000023";
        String devCode = client().post("/api/auth/register", registration(phone)).expectStatus(200).json().get("devCode").stringValue();
        doThrow(new IllegalStateException("hỏng khi tạo hồ sơ")).when(patientService).createPatient(any(), any(), any(), any());
        client().post("/api/auth/register/verify", Map.of("phone", phone, "code", devCode)).expectStatus(500);
        assertThat(count("users")).isEqualTo(2);
        assertThat(count("patients")).isZero();
    }

    @Test
    @DisplayName("[java] remember=false: cookie phiên không có Max-Age và không nhớ thiết bị")
    void khongNhoThietBi() {
        client().registerUser("0911000024", "Nguyễn Văn A");
        assertThat(count("trusted_devices")).isEqualTo(1);

        var other = client();
        String devCode = other.post("/api/auth/login", Map.of("phone", "0911000024")).expectStatus(200).json().get("devCode").stringValue();
        var res = other.post("/api/auth/login/verify", Map.of("phone", "0911000024", "code", devCode, "remember", false)).expectStatus(200);
        assertThat(setCookies(res)).hasSize(1).allSatisfy(c -> assertThat(c).startsWith("ct_session=").doesNotContain("Max-Age"));
        assertThat(count("trusted_devices")).isEqualTo(1);
        // Không nhớ thiết bị thì đăng nhập lần sau vẫn phải nhập mã
        backdate("0911000024");
        assertThat(other.post("/api/auth/login", Map.of("phone", "0911000024")).expectStatus(200).json().get("needCode").booleanValue())
                .isTrue();
    }

    @Test
    @DisplayName("[java] remember mặc định: nhớ thiết bị, cookie thiết bị chỉ cấp một lần")
    void nhoThietBiMacDinh() {
        var first = client().registerUser("0911000025", "Nguyễn Văn A");
        first.post("/api/auth/logout", Map.of()).expectStatus(200);
        // Cùng thiết bị: đăng nhập ngay, không cấp lại cookie thiết bị
        var again = first.post("/api/auth/login", Map.of("phone", "0911000025")).expectStatus(200);
        assertThat(again.json().get("needCode").booleanValue()).isFalse();
        assertThat(again.json().get("user").get("phone").stringValue()).isEqualTo("0911000025");
        assertThat(setCookies(again)).hasSize(1).allSatisfy(c -> assertThat(c).startsWith("ct_session=").contains("Max-Age=2592000"));

        var other = client();
        String devCode = other.post("/api/auth/login", Map.of("phone", "0911000025")).expectStatus(200).json().get("devCode").stringValue();
        var res = other.post("/api/auth/login/verify", Map.of("phone", "0911000025", "code", devCode)).expectStatus(200);
        assertThat(setCookies(res)).anySatisfy(c -> assertThat(c).startsWith("ct_session=").contains("Max-Age=2592000"))
                .anySatisfy(c -> assertThat(c).startsWith("ct_device="));
        assertThat(count("trusted_devices")).isEqualTo(2);
        // Đã có cookie thiết bị thì xác nhận lại không cấp cookie mới
        other.post("/api/auth/logout", Map.of());
        var withDevice = other.post("/api/auth/login", Map.of("phone", "0911000025")).expectStatus(200);
        assertThat(withDevice.json().get("needCode").booleanValue()).isFalse();
    }

    @Test
    @DisplayName("[java] đăng nhập: tài khoản bị khoá 403, số của nội bộ hoặc số lạ 404")
    void dangNhapTaiKhoanKhoaHoacLa() {
        User user = createUser("USER");
        user.setActive(false);
        users.save(user);
        var locked = client().post("/api/auth/login", Map.of("phone", user.getPhone())).expectStatus(403);
        assertThat(errorMessage(locked)).isEqualTo("Tài khoản đang bị khoá. Cô chú vui lòng gọi phòng khám để được hỗ trợ.");
        var staff = client().post("/api/auth/login", Map.of("phone", "0900000002")).expectStatus(404);
        assertThat(errorMessage(staff)).isEqualTo("Số điện thoại này chưa có tài khoản. Cô chú bấm \"Tạo tài khoản\" để đăng ký.");
        client().post("/api/auth/login", Map.of("phone", "0911999999")).expectStatus(404);
    }

    @Test
    @DisplayName("[java] tài khoản nội bộ mới đổi mật khẩu tạm rồi mới làm việc được")
    void noiBoMoiDoiMatKhauTam() {
        User staff = new User();
        staff.setPhone("0900000099");
        staff.setEmail("letanmoi@chuctu.vn");
        staff.setFullName("Lễ Tân Mới");
        staff.setRole("RECEPTIONIST");
        staff.setPasswordHash(auth.hashPassword("TamThoi123"));
        staff.setMustChangePassword(true);
        users.save(staff);

        var agent = client();
        var login = agent.post("/api/auth/staff/login", Map.of("identifier", "0900000099", "password", "TamThoi123")).expectStatus(200);
        assertThat(login.json().get("user").get("mustChangePassword").booleanValue()).isTrue();
        agent.get("/api/staff/_probe").expectStatus(403);
        var changed = agent.post("/api/auth/change-password", Map.of("currentPassword", "TamThoi123", "newPassword", "MatKhauMoi456"))
                .expectStatus(200);
        assertThat(changed.json().get("user").get("mustChangePassword").booleanValue()).isFalse();
        agent.get("/api/staff/_probe").expectStatus(200);
    }

    @Test
    @DisplayName("[java] đăng nhập nội bộ: số có dấu phân cách, email viết hoa, tài khoản khoá, vai trò USER")
    void dangNhapNoiBoCacTruongHop() {
        client().post("/api/auth/staff/login", Map.of("identifier", "  0900.000.002 ", "password", "ChucTu@2026")).expectStatus(200);
        client().post("/api/auth/staff/login", Map.of("identifier", "LETAN@CHUCTU.VN", "password", "ChucTu@2026")).expectStatus(200);
        var last = staffByEmail("letan@chuctu.vn");
        assertThat(last.getLastLoginAt()).isNotNull();

        // Khoá: sai mật khẩu vẫn 401, đúng mật khẩu thì 403 có thông báo riêng
        last.setActive(false);
        users.save(last);
        client().post("/api/auth/staff/login", Map.of("identifier", "letan@chuctu.vn", "password", "sai")).expectStatus(401);
        var locked = client().post("/api/auth/staff/login", Map.of("identifier", "letan@chuctu.vn", "password", "ChucTu@2026"))
                .expectStatus(403);
        assertThat(errorMessage(locked)).isEqualTo("Tài khoản đang bị khoá. Vui lòng liên hệ quản trị viên.");

        // Người dùng thường không đăng nhập bằng mật khẩu, kể cả khi có mật khẩu
        User user = createUser("USER");
        user.setPasswordHash(auth.hashPassword("MatKhau12345"));
        users.save(user);
        var res = client().post("/api/auth/staff/login", Map.of("identifier", user.getPhone(), "password", "MatKhau12345")).expectStatus(401);
        assertThat(errorMessage(res)).isEqualTo("Số điện thoại/email hoặc mật khẩu chưa đúng.");

        var empty = client().post("/api/auth/staff/login", "{\"identifier\":\"  \",\"password\":\"\"}").expectStatus(400);
        assertThat(fields(empty).get("identifier").stringValue()).isEqualTo("Vui lòng nhập số điện thoại hoặc email.");
        assertThat(fields(empty).get("password").stringValue()).isEqualTo("Vui lòng nhập mật khẩu.");
        var missing = client().post("/api/auth/staff/login", "{}").expectStatus(400);
        assertThat(fields(missing).get("identifier").stringValue()).isEqualTo("Required");
    }

    @Test
    @DisplayName("[java] mật khẩu do bản Node băm (bcryptjs) vẫn đăng nhập được, kể cả mật khẩu dài quá 72 byte")
    void matKhauBcryptCuaNode() {
        // Hai chuỗi băm do bcryptjs 2.4.3 tạo từ backend/src (hashPassword của auth.service.js)
        String nodeHash = "$2a$10$G4OY7KIwMHuOGQqUnl1f4.CdJFiAZ34SAgohOqF4D8uieX1S91sG2";
        String longHash = "$2a$10$b2100eDav30ztoBT26XIUupIJGKOtJ7nzRdhoFZ2JeIp6e3XNT92u";
        User a = staffByEmail("letan@chuctu.vn");
        a.setPasswordHash(nodeHash);
        users.save(a);
        client().post("/api/auth/staff/login", Map.of("identifier", "letan@chuctu.vn", "password", "MatKhauNode@2026")).expectStatus(200);
        client().post("/api/auth/staff/login", Map.of("identifier", "letan@chuctu.vn", "password", "ChucTu@2026")).expectStatus(401);

        User b = staffByEmail("admin@chuctu.vn");
        b.setPasswordHash(longHash);
        users.save(b);
        client().post("/api/auth/staff/login", Map.of("identifier", "admin@chuctu.vn",
                "password", "Mật khẩu dài " + "x".repeat(70))).expectStatus(200);
    }

    @Test
    @DisplayName("[java] đổi sang mật khẩu dài quá 72 byte không lỗi")
    void matKhauDaiQua72Byte() {
        for (String longPassword : List.of("m".repeat(100), "ế".repeat(40) + "abc")) {
            var agent = client().staff();
            agent.post("/api/auth/change-password", Map.of("currentPassword", "ChucTu@2026", "newPassword", longPassword)).expectStatus(200);
            client().post("/api/auth/staff/login", Map.of("identifier", "letan@chuctu.vn", "password", longPassword)).expectStatus(200);
            // Đổi lại để vòng sau bắt đầu từ mật khẩu mẫu
            agent.post("/api/auth/change-password", Map.of("currentPassword", longPassword, "newPassword", "ChucTu@2026")).expectStatus(200);
        }
    }
}
