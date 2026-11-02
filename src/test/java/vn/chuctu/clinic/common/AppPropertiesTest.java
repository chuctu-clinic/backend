package vn.chuctu.clinic.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppPropertiesTest {

    private static String message(MockEnvironment env) {
        return assertThrows(IllegalStateException.class, () -> new AppProperties(env)).getMessage();
    }

    @Test
    @DisplayName("[java] giá trị mặc định giống config.js")
    void defaults() {
        AppProperties p = new AppProperties(new MockEnvironment());
        assertEquals("development", p.env);
        assertFalse(p.isProd);
        assertEquals(4000, p.port);
        assertEquals(List.of("http://localhost:5173"), p.clientOrigin);
        assertEquals("console", p.smsProvider);
        assertEquals("console", p.emailProvider);
        assertEquals(587, p.smtp.port());
        assertEquals("123456", p.emailDevCode);
        assertEquals("admin@chuctu.vn", p.seedAdmin.email());
        assertEquals("ChucTu@2026", p.seedAdmin.password());
        assertEquals("0900000002", p.seedReceptionist.phone());
        assertTrue(p.jwtSecret.length() >= 32);
    }

    @Test
    @DisplayName("[java] biến rỗng coi như không đặt, CLIENT_ORIGIN tách theo dấu phẩy, mật khẩu SMTP bỏ khoảng trắng")
    void parsing() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("PORT", "")
                .withProperty("CLIENT_ORIGIN", "http://a.vn, http://b.vn")
                .withProperty("EMAIL_DEV_CODE", "12ab56")
                .withProperty("SMTP_PASS", "abcd efgh\tijkl");
        AppProperties p = new AppProperties(env);
        assertEquals(4000, p.port);
        assertEquals(List.of("http://a.vn", "http://b.vn"), p.clientOrigin);
        assertEquals("123456", p.emailDevCode);
        assertEquals("abcdefghijkl", p.smtp.pass());
        assertEquals("654321", new AppProperties(new MockEnvironment().withProperty("EMAIL_DEV_CODE", "654321")).emailDevCode);
    }

    @Test
    @DisplayName("[java] EMAIL_PROVIDER phải là console hoặc smtp, smtp thì cần đủ cấu hình")
    void emailProvider() {
        assertTrue(message(new MockEnvironment().withProperty("EMAIL_PROVIDER", "sendgrid")).contains("console hoặc smtp"));
        String missing = message(new MockEnvironment().withProperty("EMAIL_PROVIDER", "smtp").withProperty("SMTP_HOST", "h"));
        assertTrue(missing.contains("SMTP_USER, SMTP_PASS, MAIL_FROM"), missing);
        new AppProperties(new MockEnvironment().withProperty("EMAIL_PROVIDER", "smtp").withProperty("SMTP_HOST", "h")
                .withProperty("SMTP_USER", "u").withProperty("SMTP_PASS", "p").withProperty("MAIL_FROM", "f@x.vn"));
    }

    @Test
    @DisplayName("[java] production không chạy với JWT_SECRET mặc định, mẫu hoặc ngắn hơn 32 ký tự")
    void productionSecret() {
        for (String secret : new String[] {null, "doi-chuoi-nay-khi-trien-khai", "dev-secret-chuc-tu", "ngan"}) {
            MockEnvironment env = new MockEnvironment().withProperty("NODE_ENV", "production");
            if (secret != null) env.setProperty("JWT_SECRET", secret);
            assertTrue(message(env).contains("JWT_SECRET"), String.valueOf(secret));
        }
        MockEnvironment ok = new MockEnvironment().withProperty("NODE_ENV", "production")
                .withProperty("JWT_SECRET", "x".repeat(40));
        assertTrue(new AppProperties(ok).isProd);
    }

    @Test
    @DisplayName("[java] kiểm thử luôn dùng tài khoản mẫu, bỏ qua SEED_*; production không có mật khẩu mẫu")
    void seedAccounts() {
        MockEnvironment test = new MockEnvironment().withProperty("NODE_ENV", "test")
                .withProperty("SEED_ADMIN_EMAIL", "Khac@x.vn").withProperty("SEED_ADMIN_PASSWORD", "Khac@1234");
        AppProperties t = new AppProperties(test);
        assertTrue(t.isTest);
        assertEquals("admin@chuctu.vn", t.seedAdmin.email());
        assertEquals("ChucTu@2026", t.seedAdmin.password());

        MockEnvironment dev = new MockEnvironment().withProperty("SEED_ADMIN_EMAIL", "Khac@X.vn");
        assertEquals("khac@x.vn", new AppProperties(dev).seedAdmin.email());

        AppProperties prod = new AppProperties(new MockEnvironment().withProperty("NODE_ENV", "production")
                .withProperty("JWT_SECRET", "y".repeat(40)));
        assertEquals("", prod.seedAdmin.password());
    }

    @Test
    @DisplayName("[java] FIREBASE_TEST_NUMBERS_ONLY không có tác dụng khi production")
    void firebaseTestNumbers() {
        assertTrue(new AppProperties(new MockEnvironment().withProperty("FIREBASE_TEST_NUMBERS_ONLY", "1")).firebase.testNumbersOnly());
        assertFalse(new AppProperties(new MockEnvironment().withProperty("NODE_ENV", "production")
                .withProperty("JWT_SECRET", "z".repeat(40)).withProperty("FIREBASE_TEST_NUMBERS_ONLY", "1")).firebase.testNumbersOnly());
    }
}
