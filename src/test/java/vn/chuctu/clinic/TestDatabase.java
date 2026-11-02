package vn.chuctu.clinic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Đưa kiểm thử sang database chuctu_test: lấy DB_URL đã nạp (từ .env hoặc biến môi trường) rồi đổi tên database.
 * Kiểm thử không bao giờ chạy trên database khác, vì mỗi test xoá sạch mọi bảng.
 */
public final class TestDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String NAME = "chuctu_test";
    private static final Pattern MYSQL_URL = Pattern.compile("^(jdbc:mysql://[^/?]+)/([^?]+)(\\?.*)?$");

    /** DB_URL của ứng dụng đổi tên database thành chuctu_test. */
    public static String testUrl(String dbUrl) {
        Matcher m = dbUrl == null ? null : MYSQL_URL.matcher(dbUrl);
        if (m == null || !m.matches()) {
            throw new IllegalStateException("DB_URL phải có dạng jdbc:mysql://host[:port]/tên_database[?tham_số]");
        }
        return m.group(1) + "/" + NAME + (m.group(3) == null ? "" : m.group(3));
    }

    /** Chỉ cho xoá dữ liệu khi database đang nối chính xác là chuctu_test. */
    public static void assertSafeToWipe(String currentDatabase) {
        if (!NAME.equals(currentDatabase)) {
            throw new IllegalStateException(
                    "Từ chối xoá dữ liệu: kiểm thử đang nối database \"" + currentDatabase + "\" thay vì " + NAME);
        }
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String url = testUrl(context.getEnvironment().getProperty("DB_URL"));
        // NoShowJob không được chạy trong test: nó tự huỷ phiếu theo giờ thật giữa chừng các test
        TestPropertyValues.of("spring.datasource.url=" + url, "clinic.no-show-job.enabled=false").applyTo(context);
    }
}
