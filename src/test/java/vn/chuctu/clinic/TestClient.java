package vn.chuctu.clinic;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trình duyệt giả cho kiểm thử, giữ cookie như request.agent của supertest. Gọi qua HTTP thật tới 127.0.0.1
 * (không dùng localhost để khỏi lẫn cookie ct_session với các bản chạy khác trên máy).
 */
public final class TestClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final String DEFAULT_PASSWORD = "ChucTu@2026";

    /** Phản hồi: status() và json() như res.status / res.body của supertest. */
    public record Result(int status, String text, HttpHeaders headers) {
        public JsonNode json() {
            return MAPPER.readTree(text);
        }

        /** Như .expect(code) của supertest. */
        public Result expectStatus(int expected) {
            if (status != expected) throw new AssertionError("Mong đợi HTTP " + expected + " nhưng nhận " + status + ": " + text);
            return this;
        }
    }

    private final URI base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public TestClient(int port) {
        this.base = URI.create("http://127.0.0.1:" + port);
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(cookies).build();
    }

    /** Gửi kèm header này ở mọi yêu cầu sau. */
    public TestClient header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /** Đặt sẵn một cookie vào trình duyệt giả (path /). */
    public TestClient cookie(String name, String value) {
        try {
            // Như thể server vừa gửi Set-Cookie, để CookieManager gắn đúng host
            cookies.put(base, Map.of("Set-Cookie", List.of(name + "=" + value + "; Path=/")));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    public Result get(String path) {
        return send("GET", path, null);
    }

    public Result post(String path, String json) {
        return send("POST", path, json);
    }

    public Result put(String path, String json) {
        return send("PUT", path, json);
    }

    public Result patch(String path, String json) {
        return send("PATCH", path, json);
    }

    public Result delete(String path) {
        return send("DELETE", path, null);
    }

    /** Nhận object bất kỳ (Map, record...) và gửi dạng JSON. */
    public Result post(String path, Object body) {
        return send("POST", path, MAPPER.writeValueAsString(body));
    }

    public Result send(String method, String path, String json) {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path));
        headers.forEach(request::header);
        if (json != null) request.header("Content-Type", "application/json");
        request.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Result(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // Ba hàm dưới đây theo test/helpers.js. Cần các API đăng ký (Task 3 trở đi) nên chưa chạy được ở Task 2.

    /** Đăng ký một người dùng mới và đăng nhập trên trình duyệt này. */
    public TestClient registerUser(String phone, String fullName) {
        Result res = post("/api/auth/register", Map.of("fullName", fullName, "phone", phone, "dob", "1960-01-01",
                "gender", "NAM", "address", "Bình Thạnh")).expectStatus(200);
        post("/api/auth/register/verify", Map.of("phone", phone, "code", res.json().get("devCode").stringValue())).expectStatus(201);
        return this;
    }

    public TestClient registerUser() {
        return registerUser("0911000001", "Nguyễn Văn A");
    }

    /** Đăng nhập tài khoản nội bộ mẫu trên trình duyệt này. */
    public TestClient staff(String identifier) {
        post("/api/auth/staff/login", Map.of("identifier", identifier, "password", DEFAULT_PASSWORD)).expectStatus(200);
        return this;
    }

    public TestClient staff() {
        return staff("letan@chuctu.vn");
    }

    /** Id hồ sơ BAN_THAN của người dùng đang đăng nhập. */
    public String selfPatientId() {
        for (JsonNode patient : get("/api/me/patients").expectStatus(200).json().get("items")) {
            if ("BAN_THAN".equals(patient.get("relationship").stringValue())) return patient.get("id").stringValue();
        }
        throw new AssertionError("Không có hồ sơ BAN_THAN");
    }
}
