package vn.chuctu.clinic.common;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** Giờ hệ thống cắt về mili giây: MySQL DATETIME(3) lưu đúng mili giây, so sánh expectedUpdatedAt không lệch. */
    @Bean
    Clock clock() {
        return Clock.tickMillis(ZoneOffset.UTC);
    }

    @Bean
    BusinessClock businessClock(Clock clock) {
        return () -> Instant.now(clock);
    }
}
