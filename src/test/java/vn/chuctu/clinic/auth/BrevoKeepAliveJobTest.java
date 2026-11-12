package vn.chuctu.clinic.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.common.AppProperties;

/** Thay keepAlive() bằng bản đếm nên không bao giờ gọi Brevo thật. */
class BrevoKeepAliveJobTest {

    private static final AppProperties CONFIG = new AppProperties(new MockEnvironment().withProperty("NODE_ENV", "test")
            .withProperty("EMAIL_PROVIDER", "brevo").withProperty("BREVO_API_KEY", "k").withProperty("MAIL_FROM", "x@gmail.com"));

    private static BrevoMailSender brevo(AtomicInteger calls, boolean failing) {
        return new BrevoMailSender(CONFIG, JsonMapper.builder().build()) {
            @Override
            public void keepAlive() {
                calls.incrementAndGet();
                if (failing) throw new IllegalStateException("Brevo trả về 401: key not found");
            }
        };
    }

    @Test
    @DisplayName("[java] gửi qua Brevo thì gọi giữ key, lỗi không làm hỏng việc chạy định kỳ")
    void goiGiuKey() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(new BrevoKeepAliveJob(brevo(calls, false), true).run()).isTrue();
        assertThat(new BrevoKeepAliveJob(brevo(calls, true), true).run()).isFalse();
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("[java] không dùng Brevo thì không gọi gì")
    void khongDungBrevo() {
        MailSender smtp = (to, from, subject, text, html) -> { throw new AssertionError(); };
        assertThat(new BrevoKeepAliveJob(smtp, true).run()).isFalse();
    }
}
