package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Nhân viên đổi email, xác minh bằng mã gửi tới email mới (test/account-email.test.js). */
class AccountEmailTest extends IntegrationTest {

    private static JsonNode fields(TestClient.Result res) {
        return res.json().get("error").get("fields");
    }

    @Test
    @DisplayName("gửi mã tới email mới, nhập đúng mã thì lưu email")
    void guiMaRoiLuuEmail() {
        TestClient agent = client().staff("letan@chuctu.vn");
        JsonNode sent = agent.post("/api/staff/account/email", Map.of("email", "Thu.Tran@Email.com")).expectStatus(200).json();
        assertThat(sent.get("email").stringValue()).isEqualTo("thu.tran@email.com");
        assertThat(sent.get("devCode").stringValue()).matches("^\\d{6}$");

        // Chưa xác minh thì email chưa đổi
        assertThat(agent.get("/api/auth/me").expectStatus(200).json().get("user").get("email").stringValue())
                .isEqualTo("letan@chuctu.vn");

        JsonNode res = agent.post("/api/staff/account/email/verify", Map.of("code", sent.get("devCode").stringValue()))
                .expectStatus(200).json();
        assertThat(res.get("user").get("email").stringValue()).isEqualTo("thu.tran@email.com");
        client().post("/api/auth/staff/login", Map.of("identifier", "thu.tran@email.com", "password", "ChucTu@2026"))
                .expectStatus(200);
    }

    @Test
    @DisplayName("mã sai thì không đổi email")
    void maSaiKhongDoiEmail() {
        TestClient agent = client().staff();
        JsonNode sent = agent.post("/api/staff/account/email", Map.of("email", "moi@email.com")).expectStatus(200).json();
        String wrong = sent.get("devCode").stringValue().equals("000000") ? "111111" : "000000";
        var res = agent.post("/api/staff/account/email/verify", Map.of("code", wrong)).expectStatus(400);
        assertThat(fields(res).get("code").stringValue()).isNotEmpty();
        assertThat(agent.get("/api/auth/me").expectStatus(200).json().get("user").get("email").stringValue())
                .isEqualTo("letan@chuctu.vn");
    }

    @Test
    @DisplayName("email sai định dạng, trùng email cũ hoặc đã thuộc tài khoản khác thì báo lỗi ở ô email")
    void emailKhongHopLe() {
        TestClient agent = client().staff("letan@chuctu.vn");
        var res = agent.post("/api/staff/account/email", Map.of("email", "khong-phai-email")).expectStatus(400);
        assertThat(fields(res).get("email").stringValue()).isNotEmpty();
        res = agent.post("/api/staff/account/email", Map.of("email", "letan@chuctu.vn")).expectStatus(400);
        assertThat(fields(res).get("email").stringValue()).isNotEmpty();
        res = agent.post("/api/staff/account/email", Map.of("email", "admin@chuctu.vn")).expectStatus(409);
        assertThat(fields(res).get("email").stringValue()).isNotEmpty();
    }

    @Test
    @DisplayName("chưa xin mã thì không xác minh được")
    void chuaXinMa() {
        client().staff().post("/api/staff/account/email/verify", Map.of("code", "123456")).expectStatus(400);
    }

    @Test
    @DisplayName("người dùng không dùng được API này")
    void nguoiDungKhongDungDuoc() {
        client().registerUser("0911000009", "Nguyễn Văn A").post("/api/staff/account/email", Map.of("email", "a@b.com"))
                .expectStatus(403);
    }

    @Test
    @DisplayName("[java] trong lúc chờ mã, email bị tài khoản khác lấy mất thì xác minh báo EMAIL_TAKEN")
    void emailBiLayMatTrongLucCho() {
        TestClient agent = client().staff("letan@chuctu.vn");
        JsonNode sent = agent.post("/api/staff/account/email", Map.of("email", "moi@email.com")).expectStatus(200).json();
        jdbc.update("UPDATE users SET email = 'moi@email.com' WHERE email = 'admin@chuctu.vn'");
        var res = agent.post("/api/staff/account/email/verify", Map.of("code", sent.get("devCode").stringValue()))
                .expectStatus(409);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    @DisplayName("[java] thiếu email thì báo lỗi ở ô email, mã xác minh sai định dạng báo lỗi ở ô mã")
    void thieuEmailVaMaSaiDinhDang() {
        TestClient agent = client().staff();
        assertThat(fields(agent.post("/api/staff/account/email", Map.of()).expectStatus(400)).get("email").stringValue())
                .isEqualTo("Vui lòng nhập email.");
        assertThat(fields(agent.post("/api/staff/account/email/verify", Map.of("code", "12ab")).expectStatus(400))
                .get("code").stringValue()).isEqualTo("Vui lòng nhập đủ 6 số.");
    }
}
