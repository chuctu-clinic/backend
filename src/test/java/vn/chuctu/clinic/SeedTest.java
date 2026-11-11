package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.seed.Seeder;

/** Seeder (seed.js). IntegrationTest đã chạy Seeder.run(false) một lần trước mỗi test, như seed() trong helpers.js của Node. */
class SeedTest extends IntegrationTest {

    @Autowired Seeder seeder;

    private static AppProperties production(Map<String, String> vars) {
        MockEnvironment env = new MockEnvironment().withProperty("NODE_ENV", "production")
                .withProperty("JWT_SECRET", "mot-chuoi-ngau-nhien-rieng-dai-hon-32-ky-tu-abc");
        vars.forEach(env::withProperty);
        return new AppProperties(env);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    @DisplayName("[java] seed chạy hai lần không tạo trùng (tài khoản nội bộ, 4 trang thông tin)")
    void seed_chay_hai_lan_khong_tao_trung() {
        assertThat(count("users")).isEqualTo(2);
        assertThat(count("posts")).isEqualTo(4);
        seeder.run(false);
        seeder.run(false);
        assertThat(count("users")).isEqualTo(2);
        assertThat(count("posts")).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT slug FROM posts ORDER BY id", String.class))
                .containsExactly("gioi-thieu", "dich-vu", "huong-dan", "lien-he");
        assertThat(jdbc.queryForList("SELECT category FROM posts", String.class)).containsOnly("TRANG");
        assertThat(jdbc.queryForList("SELECT status FROM posts", String.class)).containsOnly("PUBLISHED");
        assertThat(jdbc.queryForList("SELECT role FROM users ORDER BY id", String.class))
                .containsExactly("ADMIN", "RECEPTIONIST");
    }

    @Test
    @DisplayName("[java] tài khoản đã có thì giữ nguyên, kể cả mật khẩu")
    void existingAccountKept() {
        jdbc.update("UPDATE users SET full_name = 'Tên đã đổi', password_hash = 'giu-nguyen' WHERE role = 'ADMIN'");
        seeder.run(false);
        assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE role = 'ADMIN'", String.class)).isEqualTo("Tên đã đổi");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE role = 'ADMIN'", String.class)).isEqualTo("giu-nguyen");
    }

    @Test
    @DisplayName("[java] production thiếu mật khẩu Admin thì báo lỗi (khớp seed.js)")
    void production_thieu_mat_khau_admin_bao_loi() {
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM users");
        assertThatThrownBy(() -> seeder.run(false, production(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Đặt SEED_ADMIN_PASSWORD trong biến môi trường để tạo tài khoản Quản trị viên đầu tiên");
        assertThatThrownBy(() -> seeder.run(false, production(Map.of("SEED_ADMIN_PASSWORD", AppProperties.SAMPLE_STAFF_PASSWORD))))
                .hasMessage("SEED_ADMIN_PASSWORD phải khác mật khẩu mẫu và dài ít nhất 8 ký tự khi chạy production");
        assertThatThrownBy(() -> seeder.run(false, production(Map.of("SEED_ADMIN_PASSWORD", "ngan"))))
                .hasMessage("SEED_ADMIN_PASSWORD phải khác mật khẩu mẫu và dài ít nhất 8 ký tự khi chạy production");
        assertThat(count("users")).isZero();
    }

    @Test
    @DisplayName("[java] production có mật khẩu Admin, không có mật khẩu Tiếp tân thì chỉ tạo Admin")
    void productionWithoutReceptionistPassword() {
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM users");
        Seeder.SeedResult result = seeder.run(false, production(Map.of("SEED_ADMIN_PASSWORD", "mat-khau-rieng-1")));
        assertThat(result.receptionist()).isNull();
        assertThat(jdbc.queryForList("SELECT role FROM users", String.class)).containsExactly("ADMIN");
        assertThat(count("posts")).isEqualTo(4);
    }

    @Test
    @DisplayName("[java] chế độ demo tạo dữ liệu mẫu đúng sức chứa khung giờ và chỉ tạo một lần")
    void demoSeedConsistent() {
        seeder.run(true);
        long users = count("users");
        long appointments = count("appointments");
        assertThat(count("posts")).isEqualTo(4 + 3 + 1);
        assertThat(appointments).isGreaterThan(50);
        assertThat(count("medical_records")).isGreaterThan(20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone = '0912345678'", Long.class)).isEqualTo(1);

        // Số chỗ giữ của mỗi khung = số phiếu chưa huỷ, không khung nào vượt 6
        List<Map<String, Object>> mismatched = jdbc.queryForList(
                "SELECT s.slot_date, s.start_time, s.`count`, "
                        + "(SELECT COUNT(*) FROM appointments a WHERE a.slot_date = s.slot_date AND a.slot_start = s.start_time "
                        + " AND a.status <> 'CANCELLED') AS active FROM slot_counters s "
                        + "HAVING `count` <> active OR `count` > 6");
        assertThat(mismatched).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments a WHERE a.status <> 'CANCELLED' AND NOT EXISTS "
                + "(SELECT 1 FROM slot_counters s WHERE s.slot_date = a.slot_date AND s.start_time = a.slot_start)", Long.class))
                .isZero();
        // Khung 9:00 ngày mai đã đủ chỗ; hàng đợi có 4 người trong phòng, chỗ 3 trống
        assertThat(jdbc.queryForObject("SELECT `count` FROM slot_counters WHERE start_time = '09:00' AND slot_date = ?",
                Integer.class, java.time.LocalDate.now(vn.chuctu.clinic.common.VnTime.ZONE).plusDays(1))).isEqualTo(6);
        assertThat(jdbc.queryForList("SELECT seat_no FROM checkins WHERE status = 'IN_ROOM' ORDER BY seat_no", Integer.class))
                .containsExactly(1, 2, 4, 5);
        assertThat(jdbc.queryForObject("SELECT `value` FROM settings WHERE name = 'queue.autoInvite'", String.class))
                .isEqualTo("false");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM patients WHERE is_locked = 1", Long.class)).isEqualTo(1);

        seeder.run(true);
        assertThat(count("users")).isEqualTo(users);
        assertThat(count("appointments")).isEqualTo(appointments);
    }
}
