package vn.chuctu.clinic.auth;

import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import vn.chuctu.clinic.common.AppProperties;

/**
 * Bộ gửi thư thật qua SMTP. Bộ gửi chỉ được tạo ở lần gửi đầu tiên (như smtpTransport.get của Node), nên khi chạy
 * với EMAIL_PROVIDER=console không cần cấu hình SMTP. Cổng 465 bật SSL trực tiếp, cổng khác dùng STARTTLS.
 * Mật khẩu SMTP_PASS đã được AppProperties bỏ dấu cách.
 */
@Component
public class SmtpMailSender implements MailSender {

    private static final String TIMEOUT_MS = "20000";

    private final AppProperties config;
    private volatile JavaMailSenderImpl sender;

    public SmtpMailSender(AppProperties config) {
        this.config = config;
    }

    JavaMailSenderImpl build() {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(config.smtp.host());
        impl.setPort(config.smtp.port());
        impl.setUsername(config.smtp.user());
        impl.setPassword(config.smtp.pass());
        impl.setDefaultEncoding("UTF-8");
        Properties props = impl.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        props.put("mail.smtp.timeout", TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MS);
        if (config.smtp.port() == 465) props.put("mail.smtp.ssl.enable", "true");
        else props.put("mail.smtp.starttls.enable", "true");
        return impl;
    }

    private JavaMailSenderImpl sender() {
        JavaMailSenderImpl s = sender;
        if (s == null) {
            synchronized (this) {
                if (sender == null) sender = build();
                s = sender;
            }
        }
        return s;
    }

    @Override
    public void send(String to, String from, String subject, String text) {
        JavaMailSenderImpl s = sender();
        try {
            MimeMessage message = s.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
            s.send(message);
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
