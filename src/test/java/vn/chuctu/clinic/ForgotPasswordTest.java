package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Quên mật khẩu của tài khoản nội bộ (test/forgot-password.test.js). */
class ForgotPasswordTest extends IntegrationTest {

    private TestClient.Result send(String email) {
        return client().post("/api/auth/staff/forgot-password", Map.of("email", email));
    }

    private TestClient.Result verify(String email, String code) {
        return client().post("/api/auth/staff/forgot-password/verify", Map.of("email", email, "code", code));
    }

    private TestClient.Result resetPw(String token, String newPassword) {
        return client().post("/api/auth/staff/reset-password", Map.of("token", token, "newPassword", newPassword));
    }

    private TestClient.Result login(String identifier, String password) {
        return client().post("/api/auth/staff/login", Map.of("identifier", identifier, "password", password));
    }

    /** Lùi giờ các mã đã gửi 61 giây để bỏ qua thời gian chờ giữa hai lần gửi. */
    private void skipResendWait() {
        jdbc.update("UPDATE verification_codes SET created_at = created_at - INTERVAL 61 SECOND");
    }

    private static String str(JsonNode node, String field) {
        return node.get(field).stringValue();
    }

    @Test
    @DisplayName("nhận mã qua email, xác nhận mã rồi đặt mật khẩu mới")
    void nhanMaDatMatKhauMoi() {
        JsonNode sent = send("LeTan@ChucTu.vn").expectStatus(200).json();
        assertThat(str(sent, "email")).isEqualTo("letan@chuctu.vn");
        assertThat(str(sent, "devCode")).matches("^\\d{6}$");

        JsonNode ok = verify("letan@chuctu.vn", str(sent, "devCode")).expectStatus(200).json();
        assertThat(str(ok, "resetToken")).isNotEmpty();
        assertThat(ok.get("expiresInSeconds").intValue()).isEqualTo(600);

        resetPw(str(ok, "resetToken"), "MatKhauMoi456").expectStatus(200);
        login("letan@chuctu.vn", "ChucTu@2026").expectStatus(401);
        login("letan@chuctu.vn", "MatKhauMoi456").expectStatus(200);
    }

    @Test
    @DisplayName("đặt lại xong thì bỏ yêu cầu đổi mật khẩu tạm")
    void boYeuCauDoiMatKhauTam() {
        jdbc.update("UPDATE users SET must_change_password = 1 WHERE email = 'letan@chuctu.vn'");
        JsonNode sent = send("letan@chuctu.vn").expectStatus(200).json();
        JsonNode ok = verify("letan@chuctu.vn", str(sent, "devCode")).expectStatus(200).json();
        resetPw(str(ok, "resetToken"), "MatKhauMoi456").expectStatus(200);
        JsonNode res = login("letan@chuctu.vn", "MatKhauMoi456").expectStatus(200).json();
        assertThat(res.get("user").get("mustChangePassword").booleanValue()).isFalse();
    }

    @Test
    @DisplayName("email sai định dạng báo lỗi theo trường")
    void emailSaiDinhDang() {
        JsonNode res = send("khong-phai-email").expectStatus(400).json();
        assertThat(str(res.get("error").get("fields"), "email")).isEqualTo("Email chưa đúng, ví dụ ten@email.com.");
    }

    @Test
    @DisplayName("email không thuộc tài khoản nội bộ đang hoạt động: trả lời như thường nhưng không gửi mã")
    void emailLaKhongGuiMa() {
        JsonNode res = send("khongco@chuctu.vn").expectStatus(200).json();
        assertThat(res.get("devCode")).isNull();
        assertThat(res.get("sent").booleanValue()).isTrue();
        verify("khongco@chuctu.vn", "123456").expectStatus(400);

        jdbc.update("UPDATE users SET is_active = 0 WHERE email = 'letan@chuctu.vn'");
        JsonNode locked = send("letan@chuctu.vn").expectStatus(200).json();
        assertThat(locked.get("devCode")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification_codes", Long.class)).isZero();
    }

    @Test
    @DisplayName("mã sai báo lỗi ở ô mã")
    void maSai() {
        JsonNode sent = send("letan@chuctu.vn").expectStatus(200).json();
        String wrong = str(sent, "devCode").equals("000000") ? "111111" : "000000";
        JsonNode res = verify("letan@chuctu.vn", wrong).expectStatus(400).json();
        assertThat(str(res.get("error").get("fields"), "code")).isNotEmpty();
    }

    @Test
    @DisplayName("gửi lại mã phải chờ 60 giây")
    void guiLaiPhaiCho() {
        send("letan@chuctu.vn").expectStatus(200);
        send("letan@chuctu.vn").expectStatus(429);
    }

    @Test
    @DisplayName("mỗi email nhận tối đa 5 mã trong một giờ")
    void toiDa5MaMotGio() {
        for (int i = 0; i < 5; i++) {
            send("letan@chuctu.vn").expectStatus(200);
            skipResendWait();
        }
        JsonNode res = send("letan@chuctu.vn").expectStatus(429).json();
        assertThat(str(res.get("error"), "message")).contains("Email này đã nhận đủ 5 mã");
    }

    @Test
    @DisplayName("mã gửi qua email không làm hết lượt mã SMS của số điện thoại")
    void khongLamHetLuotSms() {
        for (int i = 0; i < 3; i++) {
            send("letan@chuctu.vn").expectStatus(200);
            skipResendWait();
        }
        String phone = jdbc.queryForObject("SELECT phone FROM users WHERE email = 'letan@chuctu.vn'", String.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification_codes WHERE phone = ? AND email IS NULL",
                Long.class, phone)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification_codes WHERE email = 'letan@chuctu.vn'",
                Long.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("mật khẩu mới phải từ 8 ký tự")
    void matKhauMoiTu8KyTu() {
        JsonNode sent = send("letan@chuctu.vn").expectStatus(200).json();
        JsonNode ok = verify("letan@chuctu.vn", str(sent, "devCode")).expectStatus(200).json();
        JsonNode res = resetPw(str(ok, "resetToken"), "1234567").expectStatus(400).json();
        assertThat(str(res.get("error").get("fields"), "newPassword")).isNotEmpty();
    }

    @Test
    @DisplayName("mỗi lần xác nhận mã chỉ đặt lại được một lần")
    void chiDatLaiMotLan() {
        JsonNode sent = send("letan@chuctu.vn").expectStatus(200).json();
        JsonNode ok = verify("letan@chuctu.vn", str(sent, "devCode")).expectStatus(200).json();
        resetPw(str(ok, "resetToken"), "MatKhauMoi456").expectStatus(200);
        resetPw(str(ok, "resetToken"), "MatKhauKhac789").expectStatus(400);
        login("letan@chuctu.vn", "MatKhauMoi456").expectStatus(200);
    }

    @Test
    @DisplayName("token đặt lại mật khẩu không dùng làm phiên đăng nhập được")
    void tokenKhongLamPhien() {
        JsonNode sent = send("letan@chuctu.vn").expectStatus(200).json();
        JsonNode ok = verify("letan@chuctu.vn", str(sent, "devCode")).expectStatus(200).json();
        JsonNode res = client().cookie("ct_session", str(ok, "resetToken")).get("/api/auth/me").expectStatus(200).json();
        assertThat(res.get("user").isNull()).isTrue();
    }

    @Test
    @DisplayName("[java] token đặt lại hết hạn sau 10 phút")
    void tokenHetHanSau10Phut() {
        JsonNode sent = send("letan@chuctu.vn").expectStatus(200).json();
        JsonNode ok = verify("letan@chuctu.vn", str(sent, "devCode")).expectStatus(200).json();
        clock.set(clock.instant().plusSeconds(601));
        JsonNode res = resetPw(str(ok, "resetToken"), "MatKhauMoi456").expectStatus(400).json();
        assertThat(str(res.get("error"), "code")).isEqualTo("RESET_EXPIRED");
    }

    @Test
    @DisplayName("[java] token rác báo hết hạn")
    void tokenRac() {
        for (String junk : new String[] {"khong-phai-token", "a.b.c"}) {
            JsonNode res = resetPw(junk, "MatKhauMoi456").expectStatus(400).json();
            assertThat(str(res.get("error"), "code")).isEqualTo("RESET_EXPIRED");
        }
    }
}
