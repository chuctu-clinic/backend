package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.chuctu.clinic.seed.DemoDatabase;

/**
 * Chốt an toàn của chế độ demo. Các test từ chối dùng địa chỉ máy chủ không tồn tại: nếu mã lỡ kết nối thì lỗi sẽ là lỗi
 * kết nối chứ không phải lỗi từ chối, và không có database thật nào bị chạm tới.
 */
class DemoDatabaseTest extends IntegrationTest {

    /** Đầu thông điệp từ chối của DemoDatabase; lỗi kết nối không chứa câu này. */
    private static final String REFUSED = "Chế độ demo chỉ dùng database";
    private static final String NOWHERE = "jdbc:mysql://127.0.0.1:1/";

    @Test
    @DisplayName("[java] demo chỉ dùng đến chuctu_demo: tên database khác thì từ chối trước khi kết nối")
    void demo_chi_dung_den_chuctu_demo() {
        for (String name : new String[] {"chuctu", "chuctu_test", "chuctu_demo2", "Chuctu_Demo", "CHUCTU_DEMO", "mysql"}) {
            assertThatThrownBy(() -> DemoDatabase.prepare(NOWHERE + name, "u", "p", false))
                    .as(name).isInstanceOf(IllegalStateException.class).hasMessageContaining(REFUSED);
        }
        assertThatThrownBy(() -> DemoDatabase.prepare("jdbc:mysql://127.0.0.1:1", "u", "p", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(REFUSED);
        assertThatThrownBy(() -> DemoDatabase.prepare(null, "u", "p", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(REFUSED);
    }

    @Test
    @DisplayName("[java] demo bị cấm khi chạy production")
    void demo_cam_khi_production() {
        assertThatThrownBy(() -> DemoDatabase.prepare(NOWHERE + "chuctu_demo", "u", "p", true))
                .isInstanceOf(IllegalStateException.class).hasMessage("Không chạy chế độ demo khi NODE_ENV=production");
    }

    @Test
    @DisplayName("[java] DB_URL của ứng dụng đổi tên database thành chuctu_demo, giữ nguyên tham số")
    void demoUrl() {
        assertThat(DemoDatabase.demoUrl("jdbc:mysql://localhost:3306/chuctu")).isEqualTo("jdbc:mysql://localhost:3306/chuctu_demo");
        assertThat(DemoDatabase.demoUrl("jdbc:mysql://h/chuctu?useSSL=false&x=1")).isEqualTo("jdbc:mysql://h/chuctu_demo?useSSL=false&x=1");
        assertThatThrownBy(() -> DemoDatabase.demoUrl("jdbc:postgresql://h/chuctu")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[java] prepare xoá và tạo lại đúng chuctu_demo (utf8mb4_0900_as_ci), không đụng database khác")
    void recreatesOnlyDemoDatabase() throws Exception {
        String url = DemoDatabase.demoUrl(config.dbUrl);
        DemoDatabase.prepare(url, config.dbUsername, config.dbPassword, false);
        try (var con = DriverManager.getConnection(url, config.dbUsername, config.dbPassword); var st = con.createStatement()) {
            st.execute("CREATE TABLE marker (id INT)");
        }
        DemoDatabase.prepare(url, config.dbUsername, config.dbPassword, false);
        try (var con = DriverManager.getConnection(url, config.dbUsername, config.dbPassword); var st = con.createStatement()) {
            try (var rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'chuctu_demo'")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            try (var rs = st.executeQuery("SELECT default_collation_name FROM information_schema.schemata WHERE schema_name = 'chuctu_demo'")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("utf8mb4_0900_as_ci");
            }
        }
        // database kiểm thử đang chạy test này vẫn còn nguyên
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("chuctu_test");
    }
}
