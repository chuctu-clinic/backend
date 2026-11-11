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
        // Các việc chạy nền không được chạy trong test: NoShowJob tự huỷ phiếu theo giờ thật giữa chừng các test, seed khi khởi động
        // và dọn mã theo lịch làm đổi dữ liệu ngoài tầm kiểm soát của test (test tự gọi Seeder và VerificationCleanupJob)
        TestPropertyValues.of("spring.datasource.url=" + url, "clinic.no-show-job.enabled=false",
                "clinic.seed-on-startup=false", "clinic.cleanup-job.enabled=false").applyTo(context);
        isolateFromDeveloperEnv(context.getEnvironment().getPropertySources());
    }

    /**
     * .env của máy phát triển (SMS_PROVIDER=firebase, SMTP thật, SEED_*, CLIENT_ORIGIN...) không được lọt vào test: mọi biến
     * cấu hình trừ DB_* về rỗng, tức giá trị mặc định của AppProperties. Đặt ngay sau @TestPropertySource để test nào cần
     * giá trị riêng vẫn ghi đè được.
     */
    private static void isolateFromDeveloperEnv(org.springframework.core.env.MutablePropertySources sources) {
        java.util.Map<String, Object> blank = new java.util.HashMap<>();
        for (String key : APP_KEYS) blank.put(key, "");
        var source = new org.springframework.core.env.MapPropertySource("testIsolation", blank);
        String inlined = "Inlined Test Properties";
        if (sources.contains(inlined)) sources.addAfter(inlined, source);
        else sources.addFirst(source);
    }

    private static final java.util.List<String> APP_KEYS = java.util.List.of("JWT_SECRET", "CLIENT_ORIGIN", "SMS_PROVIDER",
            "EMAIL_PROVIDER", "SMTP_HOST", "SMTP_PORT", "SMTP_USER", "SMTP_PASS", "MAIL_FROM", "EMAIL_DEV_CODE", "FIREBASE_PROJECT_ID",
            "FIREBASE_API_KEY", "FIREBASE_AUTH_DOMAIN", "FIREBASE_APP_ID", "FIREBASE_AUTH_EMULATOR_HOST", "FIREBASE_TEST_NUMBERS_ONLY",
            "SEED_ADMIN_PHONE", "SEED_ADMIN_EMAIL", "SEED_ADMIN_NAME", "SEED_ADMIN_PASSWORD", "SEED_RECEPTIONIST_PHONE",
            "SEED_RECEPTIONIST_EMAIL", "SEED_RECEPTIONIST_NAME", "SEED_RECEPTIONIST_PASSWORD", "SEED_DOCTOR_PHONE", "SEED_DOCTOR_EMAIL",
            "SEED_DOCTOR_NAME", "SEED_DOCTOR_PASSWORD", "SEED_TECHNICIAN_PHONE", "SEED_TECHNICIAN_EMAIL", "SEED_TECHNICIAN_NAME",
            "SEED_TECHNICIAN_PASSWORD");
}
