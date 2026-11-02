package vn.chuctu.clinic.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.common.Hmac;

/**
 * Token ngắn hạn cho bước đặt mật khẩu mới: JWT HS256 {sub, pw, iat, exp} ký bằng khoá riêng hmac('reset-password'),
 * để token này không dùng làm phiên đăng nhập được (khoá ký phiên là JWT_SECRET).
 */
@Component
public class ResetTokens {

    /** Token hết hạn sau 10 phút. */
    public static final int MINUTES = 10;

    /** Người dùng và dấu mật khẩu lúc cấp token. */
    public record Claims(String sub, String pw) {}

    private static final String HEADER = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final Clock clock;
    private final JsonMapper mapper;
    private final SecretKeySpec key;
    private final AppProperties config;

    public ResetTokens(AppProperties config, Clock clock, JsonMapper mapper) {
        this.config = config;
        this.clock = clock;
        this.mapper = mapper;
        this.key = new SecretKeySpec(Hmac.sha256Hex(config.jwtSecret, "reset-password").getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
    }

    /** Dấu của mật khẩu hiện tại: đặt lại xong thì dấu đổi, token cũ hết dùng được. */
    public String passwordStamp(String passwordHash) {
        return Hmac.sha256Hex(config.jwtSecret, "pw:" + passwordHash).substring(0, 24);
    }

    public String sign(String userId, String stamp) {
        long iat = clock.instant().getEpochSecond();
        String payload = "{\"sub\":" + mapper.writeValueAsString(userId) + ",\"pw\":" + mapper.writeValueAsString(stamp)
                + ",\"iat\":" + iat + ",\"exp\":" + (iat + MINUTES * 60L) + "}";
        String signingInput = HEADER + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(hmac(signingInput));
    }

    /** Nội dung token nếu chữ ký đúng và còn hạn, ngược lại null. */
    public Claims verify(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !HEADER.equals(parts[0])) return null;
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(hmac(parts[0] + "." + parts[1]), actual)) return null;
            JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode exp = payload.get("exp");
            if (exp == null || !exp.isNumber() || exp.asLong() <= clock.instant().getEpochSecond()) return null;
            JsonNode sub = payload.get("sub");
            JsonNode pw = payload.get("pw");
            if (sub == null || !sub.isString() || pw == null || !pw.isString()) return null;
            return new Claims(sub.stringValue(), pw.stringValue());
        } catch (RuntimeException e) {
            return null;
        }
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
