package vn.chuctu.clinic;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import vn.chuctu.clinic.TestClocks.MutableBusinessClock;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.security.SessionService;
import vn.chuctu.clinic.TestClocks.MutableClock;

/**
 * Lớp cha của mọi test chạm database. Chạy trên MySQL thật (database chuctu_test), cổng ngẫu nhiên.
 * Trước mỗi test: kiểm tra đang ở chuctu_test, xoá dữ liệu mọi bảng trừ flyway_schema_history, nạp dữ liệu nền
 * (hai tài khoản nội bộ mẫu, xem seedBaselineData) và đặt lại hai đồng hồ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = TestDatabase.class)
@Import({TestClocks.class, FakeMail.Config.class})
public abstract class IntegrationTest {

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository users;
    @Autowired protected AppProperties config;
    @Autowired protected SessionService sessions;
    /** Thư đã "gửi" trong test; bộ gửi SMTP thật không bao giờ chạy. */
    @Autowired protected FakeMail mail;
    /** Đồng hồ cho mọi chỗ dùng bean java.time.Clock. */
    @Autowired protected MutableClock clock;
    /** Đồng hồ của bốn quy tắc theo giờ (tương đương clock.now của Node); mặc định 10:00 hôm nay theo giờ Việt Nam. */
    @Autowired protected MutableBusinessClock businessClock;

    /** Một trình duyệt mới (cookie riêng), tương đương request.agent(app). */
    protected TestClient client() {
        return new TestClient(port);
    }

    /** Tạo thẳng một tài khoản trong database (seed chưa có), số điện thoại theo số thứ tự để không trùng. */
    protected User createUser(String role) {
        User user = new User();
        user.setPhone("09" + String.format("%08d", users.count() + 1));
        user.setFullName("Tài khoản " + role);
        user.setRole(role);
        return users.save(user);
    }

    /** Trình duyệt mới đã đăng nhập bằng cookie phiên hợp lệ của tài khoản này. */
    protected TestClient loginAs(User user) {
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        sessions.setSession(response, user, true);
        String pair = response.getHeader("Set-Cookie").split(";")[0];
        return client().cookie(SessionService.SESSION_COOKIE, pair.substring(pair.indexOf('=') + 1));
    }

    @BeforeEach
    void resetDatabaseAndClocks() {
        wipeAllTables();
        seedBaselineData();
        clock.reset();
        businessClock.reset();
        mail.reset();
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

    /** bcrypt của mật khẩu mẫu, tính một lần cho cả lần chạy (mỗi lần băm mất vài chục mili giây). */
    private static final String SAMPLE_PASSWORD_HASH = new BCryptPasswordEncoder(10).encode(AppProperties.SAMPLE_STAFF_PASSWORD);

    /**
     * Nạp dữ liệu nền trước mỗi test: hai tài khoản nội bộ mẫu (admin và tiếp tân, giá trị mặc định của config.js,
     * mật khẩu ChucTu@2026). Chỉ để test dùng; Task 12 thay bằng Seeder thật.
     */
    protected void seedBaselineData() {
        for (var account : List.of(config.seedAdmin, config.seedReceptionist)) {
            String role = account == config.seedAdmin ? "ADMIN" : "RECEPTIONIST";
            jdbc.update("INSERT INTO users (phone, email, full_name, role, password_hash, must_change_password, is_active, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                    account.phone(), account.email(), account.fullName(), role, SAMPLE_PASSWORD_HASH);
        }
    }
}
