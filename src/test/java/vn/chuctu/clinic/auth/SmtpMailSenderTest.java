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

    @Test
    @DisplayName("[java] thư dựng ra có tên hiển thị và tiêu đề tiếng Việt mã hoá RFC 2047, nội dung multipart/alternative")
    void thuTiengVietMaHoaDung() throws Exception {
        String from = "Phòng khám Chúc Từ <phongkham@example.com>";
        String subject = "Phòng khám Chúc Từ - Mã đặt lại mật khẩu";
        String text = "Mã xác nhận: 123456\nPhòng khám Chúc Từ";
        String html = "<p>Mã xác nhận của bạn: <b>123456</b></p>";
        var s = sender("465");
        var message = s.buildMessage(s.build(), "letan@chuctu.vn", from, subject, text, html);
        message.saveChanges();

        var out = new java.io.ByteArrayOutputStream();
        message.writeTo(out);
        byte[] bytes = out.toByteArray();
        for (byte b : bytes) assertThat(b).as("thư chỉ gồm ký tự ASCII").isGreaterThanOrEqualTo((byte) 0);
        String raw = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(raw).containsPattern("(?m)^From: =\\?UTF-8\\?[BQ]\\?[^?]+\\?=\\s+<phongkham@example.com>")
                .containsPattern("(?m)^Subject: =\\?UTF-8\\?[BQ]\\?");

        var parsed = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()),
                new java.io.ByteArrayInputStream(bytes));
        var sender = (jakarta.mail.internet.InternetAddress) parsed.getFrom()[0];
        assertThat(sender.getPersonal()).isEqualTo("Phòng khám Chúc Từ");
        assertThat(sender.getAddress()).isEqualTo("phongkham@example.com");
        assertThat(parsed.getSubject()).isEqualTo(subject);
        assertThat(parsed.getRecipients(jakarta.mail.Message.RecipientType.TO)[0].toString()).isEqualTo("letan@chuctu.vn");

        assertThat(parsed.isMimeType("multipart/alternative")).isTrue();
        var parts = (jakarta.mail.internet.MimeMultipart) parsed.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        assertThat(parts.getBodyPart(0).isMimeType("text/plain")).isTrue();
        assertThat(parts.getBodyPart(0).getContent().toString().replace("\r\n", "\n")).isEqualTo(text);
        assertThat(parts.getBodyPart(1).isMimeType("text/html")).isTrue();
        assertThat(parts.getBodyPart(1).getContent()).isEqualTo(html);
    }
}
