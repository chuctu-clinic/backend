package vn.chuctu.clinic.auth;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSenderImpl;
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

    /**
     * Dựng thư không kết nối: tên hiển thị trong From và tiêu đề mã hoá RFC 2047 (UTF-8), nội dung là
     * multipart/alternative gồm bản văn bản rồi bản HTML.
     */
    MimeMessage buildMessage(JavaMailSenderImpl s, String to, String from, String subject, String text, String html)
            throws MessagingException, java.io.UnsupportedEncodingException {
        MimeMessage message = s.createMimeMessage();
        InternetAddress sender = new InternetAddress(from);
        if (sender.getPersonal() != null) sender.setPersonal(sender.getPersonal(), "UTF-8");
        message.setFrom(sender);
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        message.setSubject(subject, "UTF-8");
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText(text, "UTF-8", "plain");
        alternative.addBodyPart(plain);
        MimeBodyPart rich = new MimeBodyPart();
        rich.setText(html, "UTF-8", "html");
        alternative.addBodyPart(rich);
        message.setContent(alternative);
        return message;
    }

    @Override
    public void send(String to, String from, String subject, String text, String html) {
        JavaMailSenderImpl s = sender();
        try {
            s.send(buildMessage(s, to, from, subject, text, html));
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
