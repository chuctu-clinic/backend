package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.security.SessionService;

class SessionTest extends IntegrationTest {

    private static final String HS256 = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    @Autowired AppProperties config;
    @Autowired JsonMapper mapper;

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Tự ký một JWT HS256 để dựng các token mà SessionService không tự tạo. */
    private static String jwt(String secret, String header, String payload) throws Exception {
        String input = b64(header.getBytes(StandardCharsets.UTF_8)) + "." + b64(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + b64(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String payload(String sub, long exp) {
        return "{\"sub\":\"" + sub + "\",\"role\":\"USER\",\"iat\":1,\"exp\":" + exp + "}";
    }

    private static long inOneHour() {
        return Instant.now().getEpochSecond() + 3600;
    }

    private static void assertSessionCookieCleared(TestClient.Result res) {
        List<String> cookies = res.headers().allValues("set-cookie");
        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("ct_session=;").contains("Max-Age=0"));
    }

    private String tokenOf(User user) {
        var response = new MockHttpServletResponse();
        sessions.setSession(response, user, true);
        String pair = response.getHeader("Set-Cookie").split(";")[0];
        return pair.substring(pair.indexOf('=') + 1);
    }

    @Test
    @DisplayName("[java] jwt sub không phải số bị xoá cookie")
    void jwtSubKhongPhaiSoBiXoaCookie() throws Exception {
        createUser("USER");
        String token = jwt(config.jwtSecret, HS256, payload("507f1f77bcf86cd799439011", inOneHour()));
        var res = client().cookie("ct_session", token).get("/api/auth/_probe").expectStatus(200);
        assertThat(res.json().get("id").isNull()).isTrue();
        assertSessionCookieCleared(res);
        client().cookie("ct_session", token).get("/api/me/_probe").expectStatus(401);
    }

    @Test
    @DisplayName("[java] jwt sai chữ ký bị xoá cookie")
    void jwtSaiChuKyBiXoaCookie() throws Exception {
        User user = createUser("USER");
        String forged = jwt("mot-khoa-khac-hoan-toan-0123456789abcdef", HS256, payload(String.valueOf(user.getId()), inOneHour()));
        var res = client().cookie("ct_session", forged).get("/api/me/_probe").expectStatus(401);
        assertSessionCookieCleared(res);
    }

    @Test
    @DisplayName("[java] jwt hết hạn hoặc alg none không được chấp nhận")
    void jwtHetHanHoacAlgNone() throws Exception {
        User user = createUser("USER");
        String id = String.valueOf(user.getId());
        String expired = jwt(config.jwtSecret, HS256, payload(id, Instant.now().getEpochSecond() - 10));
        assertSessionCookieCleared(client().cookie("ct_session", expired).get("/api/me/_probe").expectStatus(401));
        String none = b64("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64(payload(id, inOneHour()).getBytes(StandardCharsets.UTF_8)) + ".";
        assertSessionCookieCleared(client().cookie("ct_session", none).get("/api/me/_probe").expectStatus(401));
        client().cookie("ct_session", "khong.phai.jwt").get("/api/me/_probe").expectStatus(401);
    }

    @Test
    @DisplayName("[java] tài khoản bị khoá mất phiên ngay")
    void taiKhoanBiKhoaMatPhienNgay() {
        User user = createUser("USER");
        var browser = loginAs(user);
        assertThat(browser.get("/api/me/_probe").expectStatus(200).json().get("id").stringValue())
                .isEqualTo(String.valueOf(user.getId()));
        user.setActive(false);
        users.save(user);
        assertSessionCookieCleared(browser.get("/api/me/_probe").expectStatus(401));
    }

    @Test
    @DisplayName("[java] lệch x-session-user trả 409")
    void lechXSessionUserTra409() {
        User user = createUser("USER");
        var browser = loginAs(user);
        var res = browser.header("x-session-user", "999999").get("/api/me/_probe").expectStatus(409);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("SESSION_CHANGED");
        assertThat(res.json().get("error").get("message").stringValue())
                .isEqualTo("Tài khoản trên trình duyệt này vừa thay đổi ở một thẻ khác. Trang sẽ tải lại theo tài khoản hiện tại.");
        // /api/auth/* vẫn qua để tab tự cập nhật lại
        browser.get("/api/auth/_probe").expectStatus(200);
        // Đúng tài khoản thì qua; chưa đăng nhập mà tab nhớ một tài khoản cũng bị từ chối
        loginAs(user).header("x-session-user", String.valueOf(user.getId())).get("/api/me/_probe").expectStatus(200);
        client().header("x-session-user", "5").get("/api/me/_probe").expectStatus(409);
    }

    @Test
    @DisplayName("[java] không chọn giữ đăng nhập thì không có Max-Age")
    void khongChonGiuDangNhapKhongCoMaxAge() {
        User user = createUser("USER");
        var forgetting = new MockHttpServletResponse();
        sessions.setSession(forgetting, user, false);
        assertThat(forgetting.getHeader("Set-Cookie")).startsWith("ct_session=").doesNotContain("Max-Age", "Expires")
                .contains("Path=/", "HttpOnly", "SameSite=Lax").doesNotContain("Secure");
        var remembering = new MockHttpServletResponse();
        sessions.setSession(remembering, user, true);
        assertThat(remembering.getHeader("Set-Cookie")).contains("Max-Age=2592000");
        var device = new MockHttpServletResponse();
        sessions.setDeviceCookie(device, "abc");
        assertThat(device.getHeader("Set-Cookie")).startsWith("ct_device=abc;").contains("Max-Age=31536000", "HttpOnly", "Path=/");
    }

    @Test
    @DisplayName("[java] production cookie SameSite=None và Secure")
    void productionCookieSamesiteNoneSecure() {
        var env = new MockEnvironment().withProperty("NODE_ENV", "production")
                .withProperty("JWT_SECRET", "mot-chuoi-ngau-nhien-rieng-dai-hon-ba-muoi-hai-ky-tu");
        var prod = new SessionService(new AppProperties(env), Clock.systemUTC(), mapper);
        User user = createUser("USER");
        var response = new MockHttpServletResponse();
        prod.setSession(response, user, true);
        prod.clearSession(response);
        prod.setDeviceCookie(response, "abc");
        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(4).allSatisfy(c -> assertThat(c).contains("SameSite=None", "Secure", "HttpOnly", "Path=/"));
    }

    @Test
    @DisplayName("[java] phân quyền theo URL")
    void phanQuyenTheoUrl() {
        client().get("/api/me/_probe").expectStatus(401);
        client().get("/api/staff/_probe").expectStatus(401);
        client().get("/api/admin/_probe").expectStatus(401);
        client().get("/api/auth/_probe/require").expectStatus(401);
        var unauthorized = client().get("/api/me/_probe").json().get("error");
        assertThat(unauthorized.get("code").stringValue()).isEqualTo("UNAUTHORIZED");
        assertThat(unauthorized.get("message").stringValue()).isEqualTo("Cô chú vui lòng đăng nhập để tiếp tục.");

        var user = loginAs(createUser("USER"));
        user.get("/api/me/_probe").expectStatus(200);
        var forbidden = user.get("/api/staff/_probe").expectStatus(403).json().get("error");
        assertThat(forbidden.get("code").stringValue()).isEqualTo("FORBIDDEN");
        assertThat(forbidden.get("message").stringValue()).isEqualTo("Tài khoản không có quyền thực hiện thao tác này.");
        user.get("/api/admin/_probe").expectStatus(403);

        var receptionist = loginAs(createUser("RECEPTIONIST"));
        receptionist.get("/api/staff/_probe").expectStatus(200);
        receptionist.get("/api/admin/_probe").expectStatus(403);
        receptionist.get("/api/me/_probe").expectStatus(403);

        var admin = loginAs(createUser("ADMIN"));
        admin.get("/api/staff/_probe").expectStatus(200);
        admin.get("/api/admin/_probe").expectStatus(200);
        admin.get("/api/auth/_probe/require").expectStatus(200);
    }

    @Test
    @DisplayName("[java] tài khoản nội bộ dùng mật khẩu tạm bị chặn")
    void matKhauTamBiChan() {
        User staff = createUser("RECEPTIONIST");
        staff.setMustChangePassword(true);
        users.save(staff);
        var browser = loginAs(staff);
        var res = browser.get("/api/staff/_probe").expectStatus(403);
        assertThat(res.json().get("error").get("message").stringValue()).isEqualTo("Cô chú cần đổi mật khẩu tạm trước khi tiếp tục.");
        // Vai trò sai vẫn báo thông thường; API /auth vẫn dùng được để đổi mật khẩu
        assertThat(browser.get("/api/admin/_probe").expectStatus(403).json().get("error").get("message").stringValue())
                .isEqualTo("Tài khoản không có quyền thực hiện thao tác này.");
        browser.get("/api/auth/_probe/require").expectStatus(200);
    }

    @Test
    @DisplayName("[java] cookie phiên nội bộ cũ ct_staff được chuyển sang ct_session")
    void cookieNoiBoCuDuocChuyen() {
        User staff = createUser("RECEPTIONIST");
        String token = tokenOf(staff);
        var res = client().cookie("ct_staff", token).get("/api/staff/_probe").expectStatus(200);
        List<String> cookies = res.headers().allValues("set-cookie");
        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("ct_session=" + token).contains("Max-Age=2592000"));
        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("ct_staff=;").contains("Max-Age=0"));
    }
}
