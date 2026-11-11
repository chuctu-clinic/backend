package vn.chuctu.clinic;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import vn.chuctu.clinic.TestClocks.MutableBusinessClock;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.security.SessionService;
import vn.chuctu.clinic.seed.Seeder;
import vn.chuctu.clinic.TestClocks.MutableClock;

/**
 * Lớp cha của mọi test chạm database. Chạy trên MySQL thật (database chuctu_test), cổng ngẫu nhiên.
 * Trước mỗi test: kiểm tra đang ở chuctu_test, xoá dữ liệu mọi bảng trừ flyway_schema_history, nạp dữ liệu nền
 * (Seeder.run(false): hai tài khoản nội bộ mẫu và 4 trang thông tin, như seed() trong helpers.js của Node) và đặt lại hai đồng hồ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = TestDatabase.class)
@Import({TestClocks.class, FakeMail.Config.class, FakeFirebase.Config.class})
public abstract class IntegrationTest {

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository users;
    @Autowired protected AppProperties config;
    @Autowired protected SessionService sessions;
    @Autowired protected Seeder seeder;
    /** Thư đã "gửi" trong test; bộ gửi SMTP thật không bao giờ chạy. */
    @Autowired protected FakeMail mail;
    /** Bộ kiểm token Firebase giả; bộ thật (gọi mạng) không bao giờ chạy trong test. */
    @Autowired protected FakeFirebase firebase;
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

    /**
     * Tài khoản Quản trị viên mẫu: làm được cả việc của tiếp tân (check-in) lẫn bác sĩ (gọi khám, lập phiếu kết quả), dùng cho
     * các test chỉ cần đi qua quy trình khám mà không xét phân quyền giữa các vai trò.
     */
    protected TestClient clinicStaff() {
        return client().staff("admin@chuctu.vn");
    }

    /** Bác sĩ (hoặc Quản trị viên) gọi người đã check-in của phiếu này vào khám sơ bộ. */
    protected void callIn(TestClient doctor, long appointmentId) {
        long checkinId = jdbc.queryForObject("SELECT id FROM checkins WHERE appointment_id = ? AND status = 'WAITING'", Long.class, appointmentId);
        doctor.post("/api/staff/exam/" + checkinId + "/call", TestClient.body()).expectStatus(200);
    }

    /** Bác sĩ (hoặc Quản trị viên) chỉ định điều trị cho người đang khám sơ bộ của phiếu này. */
    protected void orderTreatment(TestClient doctor, long appointmentId) {
        long checkinId = jdbc.queryForObject("SELECT id FROM checkins WHERE appointment_id = ? AND status = 'EXAMINING'", Long.class, appointmentId);
        doctor.post("/api/staff/exam/" + checkinId + "/order", TestClient.body("treatmentKinds", List.of("CHAM_CUU"))).expectStatus(200);
    }

    @BeforeEach
    void resetDatabaseAndClocks() {
        wipeAllTables();
        seeder.run(false);
        clock.reset();
        businessClock.reset();
        mail.reset();
        firebase.reset();
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
}
