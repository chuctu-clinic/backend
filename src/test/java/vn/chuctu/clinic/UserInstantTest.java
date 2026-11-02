package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.chuctu.clinic.account.User;

class UserInstantTest extends IntegrationTest {

    @Test
    @DisplayName("[java] Instant ghi qua JPA đọc lại không đổi, lưu UTC, không phụ thuộc múi giờ máy")
    void instantGhiDocLaiKhongDoi() {
        Instant at = Instant.parse("2026-11-02T03:00:00.123Z");
        User user = createUser("USER");
        user.setLastLoginAt(at);
        users.saveAndFlush(user);

        User back = users.findById(user.getId()).orElseThrow();
        assertThat(back.getLastLoginAt()).isEqualTo(at);
        assertThat(back.getCreatedAt()).isEqualTo(user.getCreatedAt());
        assertThat(back.getCreatedAt().getNano() % 1_000_000).isZero();

        String stored = jdbc.queryForObject("SELECT DATE_FORMAT(last_login_at, '%Y-%m-%d %H:%i:%s.%f') FROM users WHERE id = ?",
                String.class, user.getId());
        assertThat(stored).isEqualTo("2026-11-02 03:00:00.123000");
    }
}
