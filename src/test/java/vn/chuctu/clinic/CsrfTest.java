package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Chặn yêu cầu thay đổi dữ liệu gửi từ trang web khác (CSRF) dựa trên header Origin của trình duyệt. */
class CsrfTest extends IntegrationTest {

    @Test
    @DisplayName("[java] form POST từ trang lạ bị từ chối 403, phiên vẫn còn")
    void foreignOriginRejected() {
        var agent = loginAs(createUser("USER"));
        agent.header("Origin", "http://evil.example");
        var res = agent.send("POST", "/api/auth/logout", "x=1", "application/x-www-form-urlencoded").expectStatus(403);
        assertThat(res.json().at("/error/code").asString()).isEqualTo("FORBIDDEN");
        assertThat(res.headers().allValues("set-cookie")).isEmpty();
    }

    @Test
    @DisplayName("[java] Origin null (iframe sandbox, file) bị từ chối")
    void nullOriginRejected() {
        var agent = loginAs(createUser("USER"));
        agent.header("Origin", "null");
        agent.send("POST", "/api/auth/logout", null, null).expectStatus(403);
    }

    @Test
    @DisplayName("[java] Origin trong CLIENT_ORIGIN, cùng địa chỉ server hoặc không có Origin vẫn đi qua")
    void allowedOriginsPass() {
        loginAs(createUser("USER")).header("Origin", "http://localhost:5173").post("/api/auth/logout", "{}").expectStatus(200);
        loginAs(createUser("USER")).header("Origin", "http://127.0.0.1:" + port).post("/api/auth/logout", "{}").expectStatus(200);
        loginAs(createUser("USER")).post("/api/auth/logout", "{}").expectStatus(200);
    }

    @Test
    @DisplayName("[java] sau nginx cùng địa chỉ: Origin https cùng tên miền vẫn đi qua dù proxy báo http và không kèm cổng")
    void sameHostBehindProxy() {
        loginAs(createUser("USER")).header("Origin", "https://phongkham.example").header("X-Forwarded-Host", "phongkham.example")
                .header("X-Forwarded-Proto", "http").post("/api/auth/logout", "{}").expectStatus(200);
        loginAs(createUser("USER")).header("Origin", "http://localhost:8080").header("X-Forwarded-Host", "localhost")
                .post("/api/auth/logout", "{}").expectStatus(200);
        loginAs(createUser("USER")).header("Origin", "https://evil.example").header("X-Forwarded-Host", "phongkham.example")
                .post("/api/auth/logout", "{}").expectStatus(403);
    }

    @Test
    @DisplayName("[java] GET từ trang lạ không bị chặn (CORS lo phần đọc)")
    void foreignGetNotBlocked() {
        loginAs(createUser("USER")).header("Origin", "http://evil.example").get("/api/auth/me").expectStatus(200);
    }
}
