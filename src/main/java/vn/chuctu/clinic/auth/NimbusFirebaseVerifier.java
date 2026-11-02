package vn.chuctu.clinic.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import vn.chuctu.clinic.common.AppProperties;

/**
 * Kiểm ID token Firebase bằng Nimbus JOSE + JWT, không cần Firebase Admin SDK (chỉ cần Project ID).
 *
 * <ul>
 *   <li>Chữ ký RS256 theo khoá công khai của Google, chọn theo {@code kid}. Thuật toán khác RS256 bị từ chối.
 *   <li>{@code iss} = https://securetoken.google.com/projectId, {@code aud} chứa projectId, {@code exp} chưa qua,
 *       {@code iat} và {@code auth_time} không ở tương lai, {@code sub} khác rỗng. Thời gian lấy từ {@link Clock}.
 *   <li>Token không ký (alg=none) chỉ được nhận khi {@code allowEmulator}: tức là có FIREBASE_AUTH_EMULATOR_HOST và
 *       không phải production. Các kiểm tra claim vẫn áp dụng.
 * </ul>
 *
 * Mọi token sai đều ném {@link IllegalArgumentException}, kể cả khi không lấy được khoá công khai (lớp gọi báo
 * TOKEN_INVALID, không phải lỗi hệ thống).
 */
public final class NimbusFirebaseVerifier implements FirebaseVerifier {

    static final String GOOGLE_JWK_URL =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
    private static final String ISSUER_PREFIX = "https://securetoken.google.com/";

    private final String projectId;
    private final boolean allowEmulator;
    private final Clock clock;
    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    public NimbusFirebaseVerifier(String projectId, JWKSource<SecurityContext> keys, boolean allowEmulator, Clock clock) {
        this.projectId = projectId;
        this.allowEmulator = allowEmulator;
        this.clock = clock;
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        // Chữ ký do processor kiểm; các claim kiểm riêng ở check() để dùng đúng Clock và đủ các điều kiện
        processor.setJWTClaimsSetVerifier((claims, context) -> {});
    }

    /** Emulator chỉ bật khi có FIREBASE_AUTH_EMULATOR_HOST và không phải production (production bỏ qua biến này). */
    public static boolean emulatorAllowed(AppProperties config) {
        return !config.isProd && !config.firebase.emulatorHost().isEmpty();
    }

    /**
     * Khoá công khai của Google, tải khi cần và giữ trong bộ nhớ đệm 1 giờ; gặp kid lạ thì tải lại (có giới hạn tần
     * suất). Chờ kết nối và đọc tối đa 3 giây, phản hồi tối đa 64 KB.
     */
    public static JWKSource<SecurityContext> googleKeys() {
        try {
            var retriever = new DefaultResourceRetriever(3000, 3000, 64 * 1024);
            return JWKSourceBuilder.create(URI.create(GOOGLE_JWK_URL).toURL(), retriever).cache(3_600_000L, 15_000L).build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public FirebaseInfo verify(String idToken) {
        try {
            if (idToken == null || idToken.isBlank()) throw new IllegalArgumentException("Thiếu token");
            JWTClaimsSet claims = claimsOf(JWTParser.parse(idToken));
            Instant authTime = check(claims);
            return new FirebaseInfo(claims.getStringClaim("phone_number"), authTime);
        } catch (ParseException | JOSEException | BadJOSEException e) {
            throw new IllegalArgumentException("Token Firebase không hợp lệ: " + e.getMessage(), e);
        }
    }

    private JWTClaimsSet claimsOf(JWT jwt) throws ParseException, JOSEException, BadJOSEException {
        if (jwt instanceof PlainJWT plain) {
            if (!allowEmulator) throw new IllegalArgumentException("Token không có chữ ký");
            return plain.getJWTClaimsSet();
        }
        if (jwt instanceof SignedJWT signed) {
            JWSHeader header = signed.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())) throw new IllegalArgumentException("Thuật toán ký phải là RS256");
            if (header.getKeyID() == null || header.getKeyID().isBlank()) throw new IllegalArgumentException("Thiếu kid");
            return processor.process(signed, null);
        }
        throw new IllegalArgumentException("Loại token không được hỗ trợ");
    }

    /** Kiểm các claim theo hướng dẫn kiểm ID token của Firebase; trả về auth_time. */
    private Instant check(JWTClaimsSet claims) throws ParseException {
        Instant now = clock.instant();
        if (!(ISSUER_PREFIX + projectId).equals(claims.getIssuer())) throw new IllegalArgumentException("Sai iss");
        if (!claims.getAudience().contains(projectId)) throw new IllegalArgumentException("Sai aud");
        String sub = claims.getSubject();
        if (sub == null || sub.isEmpty()) throw new IllegalArgumentException("Thiếu sub");
        Date exp = claims.getExpirationTime();
        if (exp == null || !now.isBefore(exp.toInstant())) throw new IllegalArgumentException("Token đã hết hạn");
        Date iat = claims.getIssueTime();
        if (iat == null || iat.toInstant().isAfter(now)) throw new IllegalArgumentException("iat không hợp lệ");
        Object authTimeClaim = claims.getClaim("auth_time");
        if (!(authTimeClaim instanceof Number seconds)) throw new IllegalArgumentException("auth_time không hợp lệ");
        Instant authTime = Instant.ofEpochSecond(seconds.longValue());
        if (authTime.isAfter(now)) throw new IllegalArgumentException("auth_time ở tương lai");
        return authTime;
    }
}
