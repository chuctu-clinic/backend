package vn.chuctu.clinic.auth;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.chuctu.clinic.common.Tx;

/**
 * Thay chỉ mục TTL của MongoDB (expireAfterSeconds: 3600 trên expiresAt): mỗi 10 phút xoá mã xác nhận đã hết hạn hơn một
 * giờ. Test đặt clinic.cleanup-job.enabled=false nên không chạy theo lịch; test gọi thẳng {@link #cleanup()}.
 */
@Component
@EnableScheduling
public class VerificationCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(VerificationCleanupJob.class);
    static final Duration RETENTION = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean enabled;

    public VerificationCleanupJob(JdbcTemplate jdbc, Clock clock, @Value("${clinic.cleanup-job.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(initialDelay = 600000, fixedDelay = 600000)
    void scheduled() {
        if (!enabled) return;
        try {
            int n = cleanup();
            if (n > 0) log.info("[Dọn mã] Đã xoá {} mã xác nhận quá hạn.", n);
        } catch (RuntimeException e) {
            log.error("[Dọn mã] Lỗi: {}", e.getMessage());
        }
    }

    /** Xoá mã có expires_at cũ hơn 1 giờ so với đồng hồ; trả về số mã đã xoá. */
    public int cleanup() {
        return jdbc.update("DELETE FROM verification_codes WHERE expires_at < ?", Tx.utc(clock.instant().minus(RETENTION)));
    }
}
