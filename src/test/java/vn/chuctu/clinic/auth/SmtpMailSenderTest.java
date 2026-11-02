package vn.chuctu.clinic.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import vn.chuctu.clinic.common.AppProperties;

/** Chỉ dựng cấu hình bộ gửi thật, không bao giờ kết nối hay gửi gì. */
class SmtpMailSenderTest {

    private static SmtpMailSender sender(String port) {
        return new SmtpMailSender(new AppProperties(new MockEnvironment().withProperty("NODE_ENV", "test")
                .withProperty("EMAIL_PROVIDER", "smtp").withProperty("SMTP_HOST", "smtp.gmail.com")
                .withProperty("SMTP_PORT", port).withProperty("SMTP_USER", "a@gmail.com")
                .withProperty("SMTP_PASS", "abcd efgh ijkl mnop").withProperty("MAIL_FROM", "a@gmail.com")));
    }

    @Test
    @DisplayName("[java] cổng 465 bật SSL trực tiếp và mật khẩu ứng dụng bỏ dấu cách")
    void cong465BatSsl() {
        var impl = sender("465").build();
        assertThat(impl.getPassword()).isEqualTo("abcdefghijklmnop");
        assertThat(impl.getJavaMailProperties()).containsEntry("mail.smtp.ssl.enable", "true")
                .doesNotContainKey("mail.smtp.starttls.enable");
    }

    @Test
    @DisplayName("[java] cổng 587 dùng STARTTLS")
    void cong587DungStarttls() {
        var impl = sender("587").build();
        assertThat(impl.getPort()).isEqualTo(587);
        assertThat(impl.getJavaMailProperties()).containsEntry("mail.smtp.starttls.enable", "true")
                .doesNotContainKey("mail.smtp.ssl.enable");
    }
}
