package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContractTest extends IntegrationTest {

    @Test
    @DisplayName("[java] body sai kiểu trả VALIDATION")
    void bodySaiKieuTraValidation() {
        var res = client().post("/api/auth/login", "{\"phone\":123}").expectStatus(400);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("VALIDATION");
        assertThat(res.json().get("error").get("message").stringValue())
                .isEqualTo("Thông tin chưa hợp lệ, cô chú kiểm tra lại các ô được đánh dấu.");
        assertThat(res.json().get("error").get("fields").get("phone").stringValue()).isEqualTo("Expected string, received number");
    }

    @Test
    @DisplayName("[java] body là mảng trả VALIDATION")
    void bodyLaMangTraValidation() {
        var res = client().post("/api/auth/login", "[]").expectStatus(400);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("VALIDATION");
        assertThat(res.json().get("error").get("fields").get("_").stringValue()).isEqualTo("Expected object, received array");
    }

    @Test
    @DisplayName("[java] không gửi body vẫn báo các ô bắt buộc")
    void khongCoBody() {
        var res = client().send("POST", "/api/auth/login", null).expectStatus(400);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("VALIDATION");
        assertThat(res.json().get("error").get("fields").get("phone").stringValue()).isEqualTo("Cô chú vui lòng nhập số điện thoại.");
    }

    @Test
    @DisplayName("[java] id quá lớn trả 404 NOT_FOUND")
    void idQuaLonTra404() {
        var res = loginAs(createUser("USER")).get("/api/me/appointments/99999999999999999999").expectStatus(404);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("[java] thời điểm có 3 số mili giây")
    void thoiDiemCo3SoMiliGiay() {
        // Ba cột thời điểm của tài khoản: tròn giây, một chữ số lẻ phần chục, đủ ba chữ số mili giây
        var user = createUser("USER");
        jdbc.update("UPDATE users SET created_at = '2026-11-02 03:00:00.000', last_login_at = '2026-11-02 03:00:00.120', "
                + "info_updated_at = '2026-11-02 03:00:00.123' WHERE id = ?", user.getId());
        var res = loginAs(user).get("/api/auth/me").expectStatus(200);
        assertThat(res.text()).contains("\"createdAt\":\"2026-11-02T03:00:00.000Z\"", "\"lastLoginAt\":\"2026-11-02T03:00:00.120Z\"",
                "\"infoUpdatedAt\":\"2026-11-02T03:00:00.123Z\"");
        for (String field : new String[] {"createdAt", "lastLoginAt", "infoUpdatedAt"}) {
            assertThat(res.json().get("user").get(field).stringValue())
                    .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        }
    }

    @Test
    @DisplayName("[java] JSON quá 1 MB trả lỗi")
    void jsonQua1MbTraLoi() {
        // Bản Node (body-parser trả 413, errorHandler đổi thành 500): chạy thử cho đúng response này
        String big = "{\"phone\":\"" + "x".repeat(1100 * 1024) + "\"}";
        var res = client().post("/api/auth/login", big);
        assertThat(res.status()).isEqualTo(500);
        assertThat(res.text()).isEqualTo("{\"error\":{\"code\":\"SERVER_ERROR\",\"message\":\"Hệ thống đang bận, cô chú vui lòng thử lại sau.\"}}");
        // Dưới giới hạn thì đi tiếp bình thường
        String ok = "{\"phone\":\"" + "x".repeat(1000 * 1024) + "\"}";
        assertThat(client().post("/api/auth/login", ok).status()).isEqualTo(400);
    }

    private static void assertPhoneRequired(TestClient.Result res) {
        res.expectStatus(400);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("VALIDATION");
        assertThat(res.json().get("error").get("fields").get("phone").stringValue()).isEqualTo("Cô chú vui lòng nhập số điện thoại.");
    }

    @Test
    @DisplayName("[java] body text/plain bị bỏ qua, endpoint thấy {}")
    void bodyTextPlain() {
        assertPhoneRequired(client().send("POST", "/api/auth/login", "{\"phone\":\"0912345678\"}", "text/plain"));
    }

    @Test
    @DisplayName("[java] body form bị bỏ qua, endpoint thấy {}")
    void bodyForm() {
        assertPhoneRequired(client().send("POST", "/api/auth/login", "phone=0912345678", "application/x-www-form-urlencoded"));
    }

    @Test
    @DisplayName("[java] không có Content-Type thì body bị bỏ qua")
    void khongCoContentType() {
        assertPhoneRequired(client().send("POST", "/api/auth/login", "{\"phone\":\"0912345678\"}", null));
    }

    @Test
    @DisplayName("[java] application/json có charset vẫn được đọc")
    void jsonCoCharset() {
        String phone = createUser("USER").getPhone();
        client().send("POST", "/api/auth/login", "{\"phone\":\"" + phone + "\"}", "application/json; charset=utf-8")
                .expectStatus(200);
    }

    @Test
    @DisplayName("[java] application/x+json quá 1 MB không phải JSON nên bị bỏ qua, không lọt giới hạn")
    void xPlusJsonQuaLon() {
        String big = "{\"phone\":\"" + "x".repeat(1100 * 1024) + "\"}";
        assertPhoneRequired(client().send("POST", "/api/auth/login", big, "application/x+json"));
    }

    @Test
    @DisplayName("[java] Accept: text/plain không gây lỗi")
    void acceptTextPlain() {
        var ok = client().header("Accept", "text/plain").get("/api/auth/me").expectStatus(200);
        assertThat(ok.headers().firstValue("content-type").orElse("")).startsWith("application/json");
        client().header("Accept", "text/plain").get("/api/khong-co").expectStatus(404);
        client().header("Accept", "image/png").send("POST", "/api/auth/login", "{}").expectStatus(400);
    }

    @Test
    @DisplayName("[java] JSON gốc là chuỗi trả BAD_JSON như express.json strict")
    void jsonGocLaChuoi() {
        var res = client().post("/api/auth/login", "\"abc\"").expectStatus(400);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("BAD_JSON");
    }

    @Test
    @DisplayName("[java] header bảo mật tương đương helmet và CORS theo CLIENT_ORIGIN")
    void headerBaoMatVaCors() {
        var res = client().header("Origin", "http://localhost:5173").get("/api/auth/me").expectStatus(200);
        var h = res.headers();
        assertThat(h.firstValue("x-content-type-options")).contains("nosniff");
        assertThat(h.firstValue("x-frame-options")).contains("SAMEORIGIN");
        assertThat(h.firstValue("cross-origin-resource-policy")).contains("cross-origin");
        assertThat(h.firstValue("strict-transport-security")).contains("max-age=31536000; includeSubDomains");
        assertThat(h.firstValue("content-security-policy")).isEmpty();
        assertThat(h.firstValue("access-control-allow-origin")).contains("http://localhost:5173");
        assertThat(h.firstValue("access-control-allow-credentials")).contains("true");

        var other = client().header("Origin", "http://evil.example").get("/api/auth/me").expectStatus(200);
        assertThat(other.headers().firstValue("access-control-allow-origin")).isEmpty();
    }

    @Test
    @DisplayName("[java] preflight luôn có Vary: Access-Control-Request-Headers như cors của Node, kể cả origin lạ hay không gửi header yêu cầu")
    void preflightVary() {
        var withHeaders = client().header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type").send("OPTIONS", "/api/auth/login", null).expectStatus(204);
        assertThat(String.join(",", withHeaders.headers().allValues("vary")).toLowerCase()).contains("origin").contains("access-control-request-headers");
        assertThat(withHeaders.headers().firstValue("access-control-allow-headers")).contains("content-type");

        var stranger = client().header("Origin", "http://evil.example").header("Access-Control-Request-Method", "POST")
                .send("OPTIONS", "/api/auth/login", null).expectStatus(204);
        assertThat(String.join(",", stranger.headers().allValues("vary")).toLowerCase()).contains("origin").contains("access-control-request-headers");
        assertThat(stranger.headers().firstValue("access-control-allow-origin")).isEmpty();
        assertThat(stranger.headers().firstValue("access-control-allow-headers")).isEmpty();
    }
}
