package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** Gửi mã qua SMTP (test/email-smtp.test.js). FakeMail thay cho bộ gửi thật nên không có thư nào đi ra ngoài. */
@TestPropertySource(properties = {
        "EMAIL_PROVIDER=smtp",
        "SMTP_HOST=smtp.example.invalid",
        "SMTP_USER=phongkham@example.com",
        "SMTP_PASS=khong-gui-that",
        "MAIL_FROM=Phòng khám Chúc Từ <phongkham@example.com>"})
class EmailSmtpTest extends IntegrationTest {

    private TestClient.Result forgot() {
        return client().post("/api/auth/staff/forgot-password", Map.of("email", "letan@chuctu.vn"));
    }

    @Test
    @DisplayName("gửi email chứa mã tới đúng địa chỉ, không trả mã về giao diện")
    void guiEmailChuaMa() {
        JsonNode res = forgot().expectStatus(200).json();
        assertThat(res.get("devCode")).isNull();
        assertThat(mail.sent).hasSize(1);
        FakeMail.Message sent = mail.sent.get(0);
        assertThat(sent.to()).isEqualTo("letan@chuctu.vn");
        assertThat(sent.from()).isEqualTo(config.mailFrom);
        assertThat(sent.from()).isEqualTo("Phòng khám Chúc Từ <phongkham@example.com>");
        assertThat(sent.subject()).contains("đặt lại mật khẩu");

        // Mã trong email dùng được để xác nhận
        Matcher m = Pattern.compile("Mã xác nhận: (\\d{6})").matcher(sent.text());
        assertThat(m.find()).isTrue();
        client().post("/api/auth/staff/forgot-password/verify", Map.of("email", "letan@chuctu.vn", "code", m.group(1)))
                .expectStatus(200);
    }

    @Test
    @DisplayName("gửi không được thì báo lỗi rõ ràng và không tính lần xin mã")
    void guiKhongDuoc() {
        mail.failing = true;
        JsonNode res = forgot().expectStatus(502).json();
        assertThat(res.get("error").get("code").stringValue()).isEqualTo("EMAIL_FAILED");
        assertThat(res.get("error").get("message").stringValue())
                .isEqualTo("Chưa gửi được email. Cô chú thử lại sau ít phút hoặc liên hệ quản trị viên.");

        // Lần xin mã hỏng không bắt phải chờ 60 giây
        mail.failing = false;
        forgot().expectStatus(200);
        assertThat(mail.sent).hasSize(1);
    }
}
