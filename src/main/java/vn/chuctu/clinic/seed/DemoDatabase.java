package vn.chuctu.clinic.seed;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Database riêng của chế độ demo (profile demo). Mỗi lần khởi động xoá và tạo lại {@value #NAME}, rồi Flyway và seed chạy
 * trên đó. Chỉ đụng đúng database tên {@value #NAME}: tên khác là từ chối, trước khi kết nối. Cấm khi production.
 * Phải chạy trước khi Flyway và datasource kết nối (xem {@link DemoDatabaseInitializer}).
 */
public final class DemoDatabase {

    public static final String NAME = "chuctu_demo";
    private static final Pattern MYSQL_URL = Pattern.compile("^(jdbc:mysql://[^/?]+)/([^?]*)(\\?.*)?$");

    private DemoDatabase() {}

    /** DB_URL của ứng dụng đổi tên database thành chuctu_demo, giữ nguyên máy chủ và tham số. */
    public static String demoUrl(String dbUrl) {
        Matcher m = dbUrl == null ? null : MYSQL_URL.matcher(dbUrl);
        if (m == null || !m.matches() || m.group(2).isEmpty()) {
            throw new IllegalStateException("DB_URL phải có dạng jdbc:mysql://host[:port]/tên_database[?tham_số]");
        }
        return m.group(1) + "/" + NAME + (m.group(3) == null ? "" : m.group(3));
    }

    /**
     * Xoá rồi tạo lại database demo. {@code dbUrl} phải trỏ đúng tới {@value #NAME} (dùng {@link #demoUrl}); lệnh DROP
     * DATABASE chỉ được phát ra với tên đó.
     */
    public static void prepare(String dbUrl, String username, String password, boolean production) {
        if (production) throw new IllegalStateException("Không chạy chế độ demo khi NODE_ENV=production");
        Matcher m = dbUrl == null ? null : MYSQL_URL.matcher(dbUrl);
        if (m == null || !m.matches() || !NAME.equals(m.group(2))) {
            throw new IllegalStateException("Chế độ demo chỉ dùng database " + NAME + ", không dùng \"" + dbUrl + "\"");
        }
        // Kết nối tới máy chủ, không chọn database nào
        String serverUrl = m.group(1) + "/" + (m.group(3) == null ? "" : m.group(3));
        try (Connection con = DriverManager.getConnection(serverUrl, username, password); Statement st = con.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + NAME + "`");
            st.execute("CREATE DATABASE `" + NAME + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci");
        } catch (SQLException e) {
            throw new IllegalStateException("Không tạo lại được database " + NAME + ": " + e.getMessage(), e);
        }
    }
}
