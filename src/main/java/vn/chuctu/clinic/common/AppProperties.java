package vn.chuctu.clinic.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Cấu hình đọc từ biến môi trường và .env, cùng tên biến, giá trị mặc định và phép kiểm tra khi khởi động như
 * backend/src/config.js. Biến để trống coi như không đặt (giống {@code process.env.X || mặc định}).
 */
@Component
public final class AppProperties {

    /** Chuỗi mặc định khi phát triển, dài hơn 32 byte để ký HS256. */
    public static final String DEV_JWT_SECRET = "dev-secret-chuc-tu-chi-dung-khi-phat-trien";
    public static final String SAMPLE_STAFF_PASSWORD = "ChucTu@2026";
    private static final List<String> KNOWN_SECRETS = List.of(
            DEV_JWT_SECRET,
            "dev-secret-chuc-tu",
            "doi-chuoi-nay-khi-trien-khai",
            "doi-chuoi-nay-khi-trien-khai-thanh-chuoi-ngau-nhien");
    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");

    public record Smtp(String host, int port, String user, String pass) {}

    public record Firebase(String projectId, String apiKey, String authDomain, String appId, String emulatorHost,
            boolean testNumbersOnly) {}

    public record SeedAccount(String phone, String email, String fullName, String password) {}

    public final String env;
    public final boolean isProd;
    public final boolean isTest;
    public final int port;
    public final String jwtSecret;
    public final List<String> clientOrigin;
    public final String smsProvider;
    /** console (in mã ra log) | smtp */
    public final String emailProvider;
    public final Smtp smtp;
    public final String mailFrom;
    /** Mã mẫu cố định cho mã gửi qua email, chỉ dùng khi phát triển. */
    public final String emailDevCode;
    public final Firebase firebase;
    public final SeedAccount seedAdmin;
    public final SeedAccount seedReceptionist;
    public final SeedAccount seedDoctor;
    public final SeedAccount seedTechnician;
    public final String dbUrl;
    public final String dbUsername;
    public final String dbPassword;

    private final Environment source;

    public AppProperties(Environment source) {
        this.source = source;
        env = get("NODE_ENV", "development");
        isProd = env.equals("production");
        isTest = env.equals("test");
        port = integer("PORT", 4000);
        jwtSecret = get("JWT_SECRET", DEV_JWT_SECRET);
        clientOrigin = Arrays.stream(get("CLIENT_ORIGIN", "http://localhost:5173").split(",")).map(String::trim).toList();
        smsProvider = get("SMS_PROVIDER", "console");
        emailProvider = get("EMAIL_PROVIDER", "console");
        // Mật khẩu ứng dụng Gmail có dấu cách khi Google hiển thị; bỏ đi cho chắc
        smtp = new Smtp(get("SMTP_HOST", ""), integer("SMTP_PORT", 587), get("SMTP_USER", ""),
                get("SMTP_PASS", "").replaceAll("(?U)\\s+", ""));
        mailFrom = get("MAIL_FROM", "");
        String devCode = get("EMAIL_DEV_CODE", "");
        emailDevCode = SIX_DIGITS.matcher(devCode).matches() ? devCode : "123456";
        firebase = new Firebase(get("FIREBASE_PROJECT_ID", ""), get("FIREBASE_API_KEY", ""),
                get("FIREBASE_AUTH_DOMAIN", ""), get("FIREBASE_APP_ID", ""), get("FIREBASE_AUTH_EMULATOR_HOST", ""),
                !isProd && "1".equals(source.getProperty("FIREBASE_TEST_NUMBERS_ONLY")));
        seedAdmin = seedAccount("ADMIN", "0900000001", "admin@chuctu.vn", "Quản trị viên");
        seedReceptionist = seedAccount("RECEPTIONIST", "0900000002", "letan@chuctu.vn", "Trần Thị Thu");
        seedDoctor = seedAccount("DOCTOR", "0900000003", "bacsi@chuctu.vn", "Nguyễn Văn An");
        seedTechnician = seedAccount("TECHNICIAN", "0900000004", "ktv@chuctu.vn", "Phạm Thị Hoa");
        dbUrl = get("DB_URL", "");
        dbUsername = get("DB_USERNAME", "");
        dbPassword = get("DB_PASSWORD", "");
        validate();
    }

    private void validate() {
        if (!List.of("console", "smtp").contains(emailProvider)) {
            throw new IllegalStateException("EMAIL_PROVIDER phải là console hoặc smtp (đang là \"" + emailProvider + "\")");
        }
        if (emailProvider.equals("smtp")) {
            List<String> missing = new ArrayList<>();
            if (smtp.host().isEmpty()) missing.add("SMTP_HOST");
            if (smtp.user().isEmpty()) missing.add("SMTP_USER");
            if (smtp.pass().isEmpty()) missing.add("SMTP_PASS");
            if (mailFrom.isEmpty()) missing.add("MAIL_FROM");
            if (!missing.isEmpty()) {
                throw new IllegalStateException("EMAIL_PROVIDER=smtp cần đặt " + String.join(", ", missing) + " trong .env");
            }
        }
        // Khoá ký phiên và băm mã xác nhận: khi production không được dùng giá trị mặc định, chuỗi mẫu trong
        // .env.example / docker-compose.yml (ai đọc mã nguồn cũng biết) hay chuỗi quá ngắn.
        if (isProd && (KNOWN_SECRETS.contains(jwtSecret) || jwtSecret.length() < 32)) {
            throw new IllegalStateException("JWT_SECRET phải là chuỗi ngẫu nhiên riêng, dài ít nhất 32 ký tự khi chạy production");
        }
    }

    /** Biến rỗng hay vắng đều lấy giá trị mặc định. */
    private String get(String name, String fallback) {
        String value = source.getProperty(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private int integer(String name, int fallback) {
        String value = get(name, String.valueOf(fallback));
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + " phải là số nguyên (đang là \"" + value + "\")");
        }
    }

    /** Khi kiểm thử luôn dùng giá trị mẫu; production không có mật khẩu mẫu. */
    private String seed(String name, String fallback) {
        if (isTest) return fallback;
        String value = source.getProperty(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private SeedAccount seedAccount(String role, String phone, String email, String fullName) {
        String prefix = "SEED_" + role + "_";
        return new SeedAccount(
                seed(prefix + "PHONE", phone),
                seed(prefix + "EMAIL", email).toLowerCase(),
                seed(prefix + "NAME", fullName),
                seed(prefix + "PASSWORD", isProd ? "" : SAMPLE_STAFF_PASSWORD));
    }
}
