package vn.chuctu.clinic;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vn.chuctu.clinic.auth.FirebaseVerifier;

/**
 * Bộ kiểm token Firebase giả cho kiểm thử (tương đương việc thay firebaseVerifier.verify trong firebase.test.js). Là bean
 * @Primary nên bộ kiểm thật không bao giờ gọi mạng. Chỉ nhận đúng {@link #TOKEN}; số điện thoại và tuổi của lần xác thực
 * do từng test đặt, {@link #reset()} đưa về mặc định trước mỗi test.
 */
public final class FakeFirebase implements FirebaseVerifier {

    public static final String TOKEN = "token-hop-le-cua-firebase";

    private final Clock clock;
    public volatile String phone;
    public volatile long authAgeMillis;

    public FakeFirebase(Clock clock) {
        this.clock = clock;
    }

    @Override
    public FirebaseInfo verify(String idToken) {
        if (!TOKEN.equals(idToken)) throw new IllegalArgumentException("invalid");
        return new FirebaseInfo(phone, clock.instant().minusMillis(authAgeMillis));
    }

    public void reset() {
        phone = null;
        authAgeMillis = 0;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        FakeFirebase fakeFirebase(Clock clock) {
            return new FakeFirebase(clock);
        }
    }
}
