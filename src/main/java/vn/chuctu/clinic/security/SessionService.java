package vn.chuctu.clinic.security;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.common.Rules;

/**
 * Cookie phiên đăng nhập (JWT HS256) và cookie thiết bị, tương đương setSession, clearSession, setDeviceCookie
 * trong middleware/auth.js. Một phiên cho cả trang người dùng và khu nội bộ; quyền theo vai trò của tài khoản.
 */
@Service
public class SessionService {

    public static final String SESSION_COOKIE = "ct_session";
    /** Cookie phiên nội bộ của bản trước (hai phiên riêng): còn thì chuyển sang ct_session. */
    public static final String LEGACY_STAFF_COOKIE = "ct_staff";
    public static final String DEVICE_COOKIE = "ct_device";

    private static final Duration SESSION_MAX_AGE = Duration.ofDays(Rules.SESSION_DAYS);
    private static final Duration DEVICE_MAX_AGE = Duration.ofDays(365);
    private static final String HEADER = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private static final Pattern USER_ID = Pattern.compile("^[1-9]\\d{0,18}$");

    private final AppProperties config;
    private final Clock clock;
    private final JsonMapper mapper;
    private final SecretKeySpec key;

    public SessionService(AppProperties config, Clock clock, JsonMapper mapper) {
        this.config = config;
        this.clock = clock;
        this.mapper = mapper;
        this.key = new SecretKeySpec(config.jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Không chọn "giữ đăng nhập" thì cookie không có Max-Age: phiên kết thúc khi đóng trình duyệt. */
    public void setSession(HttpServletResponse response, User user, boolean remember) {
        addCookie(response, cookie(SESSION_COOKIE, sign(user), remember ? SESSION_MAX_AGE : null));
    }

    /** Cấp lại cookie phiên cho token cũ vừa chuyển từ cookie nội bộ của bản trước. */
    void setSessionToken(HttpServletResponse response, String token) {
        addCookie(response, cookie(SESSION_COOKIE, token, SESSION_MAX_AGE));
    }

    /** Xoá phiên, kể cả cookie phiên nội bộ cũ. */
    public void clearSession(HttpServletResponse response) {
        for (String name : new String[] {SESSION_COOKIE, LEGACY_STAFF_COOKIE}) clearCookie(response, name);
    }

    void clearCookie(HttpServletResponse response, String name) {
        addCookie(response, cookie(name, "", Duration.ZERO));
    }

    public void setDeviceCookie(HttpServletResponse response, String deviceId) {
        addCookie(response, cookie(DEVICE_COOKIE, deviceId, DEVICE_MAX_AGE));
    }

    /** JWT HS256 {sub, role, iat, exp}; sub là id tài khoản. */
    String sign(User user) {
        long iat = clock.instant().getEpochSecond();
        long exp = iat + SESSION_MAX_AGE.toSeconds();
        String payload = "{\"sub\":\"" + user.getId() + "\",\"role\":\"" + user.getRole() + "\",\"iat\":" + iat + ",\"exp\":" + exp + "}";
        String signingInput = HEADER + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(hmac(signingInput));
    }

    /**
     * Id tài khoản trong token hợp lệ (chữ ký HS256 đúng, còn hạn, sub là số), ngược lại null.
     * Token của bản Node cũ có sub 24 ký tự hex nên bị từ chối ở đây.
     */
    Long verify(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !HEADER.equals(parts[0])) return null;
            byte[] expected = hmac(parts[0] + "." + parts[1]);
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) return null;
            JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode exp = payload.get("exp");
            if (exp == null || !exp.isNumber() || exp.asLong() <= clock.instant().getEpochSecond()) return null;
            JsonNode sub = payload.get("sub");
            if (sub == null || !sub.isString() || !USER_ID.matcher(sub.stringValue()).matches()) return null;
            return Long.parseLong(sub.stringValue());
        } catch (RuntimeException e) {
            // base64 hỏng, JSON hỏng, sub quá lớn: đều là token sai
            return null;
        }
    }

    private ResponseCookie cookie(String name, String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(config.isProd)
                .sameSite(config.isProd ? "None" : "Lax")
                .path("/");
        if (maxAge != null) builder.maxAge(maxAge);
        return builder.build();
    }

    private static void addCookie(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
