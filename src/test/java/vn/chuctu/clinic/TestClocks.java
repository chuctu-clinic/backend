package vn.chuctu.clinic;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vn.chuctu.clinic.common.BusinessClock;
import vn.chuctu.clinic.common.VnTime;

/** Hai đồng hồ đổi được cho kiểm thử, thay bản thật nhờ @Primary. Mỗi test được đặt lại về mặc định. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClocks {

    /** Giống DEFAULT_NOW của test Node: 10:00 hôm nay theo giờ Việt Nam, chạy lúc nào cũng vậy. */
    public static Instant defaultBusinessNow() {
        return VnTime.slotInstant(VnTime.today(Instant.now()), "10:00");
    }

    public static final class MutableClock extends Clock {
        private volatile Clock delegate = realClock();

        private static Clock realClock() {
            return Clock.tickMillis(ZoneOffset.UTC);
        }

        public void set(Instant instant) {
            delegate = Clock.fixed(instant, ZoneOffset.UTC);
        }

        public void reset() {
            delegate = realClock();
        }

        @Override
        public ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return delegate.withZone(zone);
        }

        @Override
        public Instant instant() {
            return delegate.instant();
        }
    }

    public static final class MutableBusinessClock implements BusinessClock {
        private volatile Supplier<Instant> source = TestClocks::defaultBusinessNow;

        public void set(Instant instant) {
            source = () -> instant;
        }

        public void reset() {
            source = TestClocks::defaultBusinessNow;
        }

        @Override
        public Instant now() {
            return source.get();
        }
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    MutableBusinessClock testBusinessClock() {
        return new MutableBusinessClock();
    }

}
