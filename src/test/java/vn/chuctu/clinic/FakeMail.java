package vn.chuctu.clinic;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vn.chuctu.clinic.auth.MailSender;

/**
 * Bộ gửi thư giả cho kiểm thử: ghi lại thư thay vì gửi. Là bean @Primary nên bản SMTP thật không bao giờ được gọi.
 * Bật {@link #failing} để thay cho lỗi đăng nhập SMTP.
 */
public final class FakeMail implements MailSender {

    public record Message(String to, String from, String subject, String text) {}

    public final List<Message> sent = new CopyOnWriteArrayList<>();
    public volatile boolean failing;

    @Override
    public void send(String to, String from, String subject, String text) {
        if (failing) throw new IllegalStateException("Invalid login");
        sent.add(new Message(to, from, subject, text));
    }

    public void reset() {
        sent.clear();
        failing = false;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        FakeMail fakeMail() {
            return new FakeMail();
        }
    }
}
