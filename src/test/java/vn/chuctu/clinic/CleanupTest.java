package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.chuctu.clinic.auth.VerificationCleanupJob;
import vn.chuctu.clinic.common.Tx;

/** Thay chỉ mục TTL expireAfterSeconds: 3600 của MongoDB (VerificationCode.js). */
class CleanupTest extends IntegrationTest {

    @Autowired VerificationCleanupJob job;

    private void insert(String phone, Instant expiresAt) {
        jdbc.update("INSERT INTO verification_codes (phone, code_hash, purpose, expires_at, created_at) VALUES (?, 'h', 'REGISTER', ?, ?)",
                phone, Tx.utc(expiresAt), Tx.utc(expiresAt));
    }

    @Test
    @DisplayName("[java] dọn mã quá hạn một giờ: mã hết hạn hơn 1 giờ bị xoá, còn trong 1 giờ thì giữ")
    void don_ma_qua_han_mot_gio() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        clock.set(now);
        insert("0911000001", now.minus(61, ChronoUnit.MINUTES));
        insert("0911000002", now.minus(2, ChronoUnit.DAYS));
        insert("0911000003", now.minus(59, ChronoUnit.MINUTES));
        insert("0911000004", now.minus(1, ChronoUnit.MINUTES));
        insert("0911000005", now.plus(5, ChronoUnit.MINUTES));

        assertThat(job.cleanup()).isEqualTo(2);

        assertThat(jdbc.queryForList("SELECT phone FROM verification_codes ORDER BY phone", String.class))
                .containsExactly("0911000003", "0911000004", "0911000005");
    }
}
