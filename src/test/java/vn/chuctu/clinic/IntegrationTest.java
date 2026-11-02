package vn.chuctu.clinic;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import vn.chuctu.clinic.TestClocks.MutableBusinessClock;
import vn.chuctu.clinic.TestClocks.MutableClock;

/**
 * Lớp cha của mọi test chạm database. Chạy trên MySQL thật (database chuctu_test), cổng ngẫu nhiên.
 * Trước mỗi test: kiểm tra đang ở chuctu_test, xoá dữ liệu mọi bảng trừ flyway_schema_history, nạp dữ liệu nền
 * (hiện chưa có, xem seedBaselineData) và đặt lại hai đồng hồ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = TestDatabase.class)
@Import(TestClocks.class)
public abstract class IntegrationTest {

    @Autowired protected JdbcTemplate jdbc;
    /** Đồng hồ cho mọi chỗ dùng bean java.time.Clock. */
    @Autowired protected MutableClock clock;
    /** Đồng hồ của bốn quy tắc theo giờ (tương đương clock.now của Node); mặc định 10:00 hôm nay theo giờ Việt Nam. */
    @Autowired protected MutableBusinessClock businessClock;

    @BeforeEach
    void resetDatabaseAndClocks() {
        wipeAllTables();
        seedBaselineData();
        clock.reset();
        businessClock.reset();
    }

    private void wipeAllTables() {
        jdbc.execute((java.sql.Connection con) -> {
            try (var st = con.createStatement()) {
                String database;
                try (var rs = st.executeQuery("SELECT DATABASE()")) {
                    rs.next();
                    database = rs.getString(1);
                }
                TestDatabase.assertSafeToWipe(database);
                List<String> tables = new java.util.ArrayList<>();
                try (var rs = st.executeQuery("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                        + "AND table_name <> 'flyway_schema_history'")) {
                    while (rs.next()) tables.add(rs.getString(1));
                }
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                try {
                    for (String table : tables) st.execute("TRUNCATE TABLE `" + table + "`");
                } finally {
                    st.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }
            return null;
        });
    }

    /** Chỗ nạp dữ liệu nền trước mỗi test (tài khoản mẫu, cài đặt). Chưa có gì để nạp cho tới khi làm phần seed (Task 12). */
    protected void seedBaselineData() {
        // Cố ý để trống: seed chưa tồn tại.
    }
}
