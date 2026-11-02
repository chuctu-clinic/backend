package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContractTest extends IntegrationTest {

    @Test
    @DisplayName("[java] body sai kiểu trả VALIDATION")
    void bodySaiKieuTraValidation() {
        var res = client().post("/api/auth/_probe/login", "{\"phone\":123}").expectStatus(400);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("VALIDATION");
        assertThat(res.json().get("error").get("message").stringValue())
                .isEqualTo("Thông tin chưa hợp lệ, cô chú kiểm tra lại các ô được đánh dấu.");
        assertThat(res.json().get("error").get("fields").get("phone").stringValue()).isEqualTo("Expected string, received number");
    }

    @Test
    @DisplayName("[java] body là mảng trả VALIDATION")
    void bodyLaMangTraValidation() {
        var res = client().post("/api/auth/_probe/login", "[]").expectStatus(400);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("VALIDATION");
        assertThat(res.json().get("error").get("fields").get("_").stringValue()).isEqualTo("Expected object, received array");
    }

    @Test
    @DisplayName("[java] không gửi body vẫn báo các ô bắt buộc")
    void khongCoBody() {
        var res = client().send("POST", "/api/auth/_probe/login", null).expectStatus(400);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("VALIDATION");
        assertThat(res.json().get("error").get("fields").get("phone").stringValue()).isEqualTo("Cô chú vui lòng nhập số điện thoại.");
    }

    @Test
    @DisplayName("[java] id quá lớn trả 404 NOT_FOUND")
    void idQuaLonTra404() {
        var res = loginAs(createUser("USER")).get("/api/me/_probe/appointments/99999999999999999999").expectStatus(404);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("[java] thời điểm có 3 số mili giây")
    void thoiDiemCo3SoMiliGiay() {
        var res = client().get("/api/auth/_probe/instants").expectStatus(200);
        assertThat(res.text()).contains("\"whole\":\"2026-11-02T03:00:00.000Z\"", "\"tenths\":\"2026-11-02T03:00:00.120Z\"",
                "\"millis\":\"2026-11-02T03:00:00.123Z\"");
        res.json().properties().forEach(e -> assertThat(e.getValue().stringValue())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    @Test
    @DisplayName("[java] JSON quá 1 MB trả lỗi")
    void jsonQua1MbTraLoi() {
        // Bản Node (body-parser trả 413, errorHandler đổi thành 500): chạy thử cho đúng response này
        String big = "{\"phone\":\"" + "x".repeat(1100 * 1024) + "\"}";
        var res = client().post("/api/auth/_probe/login", big);
        assertThat(res.status()).isEqualTo(500);
        assertThat(res.text()).isEqualTo("{\"error\":{\"code\":\"SERVER_ERROR\",\"message\":\"Hệ thống đang bận, cô chú vui lòng thử lại sau.\"}}");
        // Dưới giới hạn thì đi tiếp bình thường
        String ok = "{\"phone\":\"" + "x".repeat(1000 * 1024) + "\"}";
        assertThat(client().post("/api/auth/_probe/login", ok).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[java] JSON gốc là chuỗi trả BAD_JSON như express.json strict")
    void jsonGocLaChuoi() {
        var res = client().post("/api/auth/_probe/login", "\"abc\"").expectStatus(400);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("BAD_JSON");
    }

    @Test
    @DisplayName("[java] header bảo mật tương đương helmet và CORS theo CLIENT_ORIGIN")
    void headerBaoMatVaCors() {
        var res = client().header("Origin", "http://localhost:5173").get("/api/auth/_probe").expectStatus(200);
        var h = res.headers();
        assertThat(h.firstValue("x-content-type-options")).contains("nosniff");
        assertThat(h.firstValue("x-frame-options")).contains("SAMEORIGIN");
        assertThat(h.firstValue("cross-origin-resource-policy")).contains("cross-origin");
        assertThat(h.firstValue("strict-transport-security")).contains("max-age=31536000; includeSubDomains");
        assertThat(h.firstValue("content-security-policy")).isEmpty();
        assertThat(h.firstValue("access-control-allow-origin")).contains("http://localhost:5173");
        assertThat(h.firstValue("access-control-allow-credentials")).contains("true");

        var other = client().header("Origin", "http://evil.example").get("/api/auth/_probe").expectStatus(200);
        assertThat(other.headers().firstValue("access-control-allow-origin")).isEmpty();
    }
}
