package vn.chuctu.clinic.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import vn.chuctu.clinic.auth.FirebaseVerifier.FirebaseInfo;
import vn.chuctu.clinic.common.AppProperties;

/**
 * Token Firebase thật được ký RS256 nên phải kiểm bằng khoá RSA: khoá được sinh ngay trong test, không gọi mạng. (Token của
 * emulator không có chữ ký nên không chứng minh được phần này.)
 */
class NimbusFirebaseVerifierTest {

    private static final String PROJECT = "chuc-tu-test";
    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static RSAKey signingKey;
    private static RSAKey otherKey;

    @BeforeAll
    static void generateKeys() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("kid-1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("kid-1").generate();
    }

    private static NimbusFirebaseVerifier verifier(boolean allowEmulator) {
        JWKSource<SecurityContext> keys = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
        return new NimbusFirebaseVerifier(PROJECT, keys, allowEmulator, CLOCK);
    }

    private static JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience(PROJECT)
                .subject(UUID.randomUUID().toString())
                .issueTime(Date.from(NOW.minusSeconds(120)))
                .expirationTime(Date.from(NOW.plusSeconds(3480)))
                .claim("auth_time", NOW.minusSeconds(120).getEpochSecond())
                .claim("phone_number", "+84911000077");
    }

    private static String sign(RSAKey key, JWSAlgorithm alg, JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static String signed(Consumer<JWTClaimsSet.Builder> tweak) throws Exception {
        JWTClaimsSet.Builder b = validClaims();
        tweak.accept(b);
        return sign(signingKey, JWSAlgorithm.RS256, b.build());
    }

    private static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    private static void assertRejected(NimbusFirebaseVerifier v, String token) {
        assertThatThrownBy(() -> v.verify(token)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("[java] token đúng được nhận, lấy đúng số điện thoại và auth_time")
    void token_dung_duoc_nhan() throws Exception {
        FirebaseInfo info = verifier(false).verify(signed(b -> {}));
        assertThat(info.phone()).isEqualTo("+84911000077");
        assertThat(info.authTime()).isEqualTo(NOW.minusSeconds(120));
    }

    @Test
    @DisplayName("[java] token không có phone_number vẫn qua chữ ký, phone là null để lớp trên báo PHONE_MISMATCH")
    void token_khong_co_so_dien_thoai() throws Exception {
        FirebaseInfo info = verifier(false).verify(signed(b -> b.claim("phone_number", null)));
        assertThat(info.phone()).isNull();
    }

    @Test
    @DisplayName("[java] sai chữ ký (ký bằng khoá khác cùng kid) bị từ chối")
    void sai_chu_ky_bi_tu_choi() throws Exception {
        assertRejected(verifier(false), sign(otherKey, JWSAlgorithm.RS256, validClaims().build()));
    }

    @Test
    @DisplayName("[java] kid không có trong bộ khoá bị từ chối")
    void kid_la_bi_tu_choi() throws Exception {
        RSAKey unknown = new RSAKeyGenerator(2048).keyID("kid-la").generate();
        assertRejected(verifier(false), sign(unknown, JWSAlgorithm.RS256, validClaims().build()));
    }

    @Test
    @DisplayName("[java] thuật toán khác RS256 bị từ chối (RS384, HS256)")
    void thuat_toan_khac_bi_tu_choi() throws Exception {
        assertRejected(verifier(false), sign(signingKey, JWSAlgorithm.RS384, validClaims().build()));

        var hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("kid-1").build(), validClaims().build());
        hs.sign(new MACSigner(new byte[32]));
        assertRejected(verifier(false), hs.serialize());
        assertRejected(verifier(true), hs.serialize());
    }

    @Test
    @DisplayName("[java] sai iss bị từ chối")
    void sai_iss_bi_tu_choi() throws Exception {
        assertRejected(verifier(false), signed(b -> b.issuer("https://securetoken.google.com/du-an-khac")));
        assertRejected(verifier(false), signed(b -> b.issuer(null)));
    }

    @Test
    @DisplayName("[java] sai aud bị từ chối")
    void sai_aud_bi_tu_choi() throws Exception {
        assertRejected(verifier(false), signed(b -> b.audience("du-an-khac")));
        assertRejected(verifier(false), signed(b -> b.audience((String) null)));
    }

    @Test
    @DisplayName("[java] hết hạn bị từ chối, kể cả đúng lúc exp")
    void het_han_bi_tu_choi() throws Exception {
        assertRejected(verifier(false), signed(b -> b.expirationTime(Date.from(NOW.minusSeconds(1)))));
        assertRejected(verifier(false), signed(b -> b.expirationTime(Date.from(NOW))));
        assertRejected(verifier(false), signed(b -> b.expirationTime(null)));
    }

    @Test
    @DisplayName("[java] iat ở tương lai hoặc thiếu bị từ chối")
    void iat_tuong_lai_bi_tu_choi() throws Exception {
        assertRejected(verifier(false), signed(b -> b.issueTime(Date.from(NOW.plusSeconds(60)))));
        assertRejected(verifier(false), signed(b -> b.issueTime(null)));
    }

    @Test
    @DisplayName("[java] auth_time ở tương lai, thiếu hoặc không phải số bị từ chối")
    void auth_time_tuong_lai_bi_tu_choi() throws Exception {
        assertRejected(verifier(false), signed(b -> b.claim("auth_time", NOW.plusSeconds(60).getEpochSecond())));
        assertRejected(verifier(false), signed(b -> b.claim("auth_time", null)));
        assertRejected(verifier(false), signed(b -> b.claim("auth_time", "khong-phai-so")));
    }

    @Test
    @DisplayName("[java] sub rỗng hoặc thiếu bị từ chối")
    void sub_rong_bi_tu_choi() throws Exception {
        assertRejected(verifier(false), signed(b -> b.subject("")));
        assertRejected(verifier(false), signed(b -> b.subject(null)));
    }

    @Test
    @DisplayName("[java] chuỗi không phải JWT bị từ chối")
    void chuoi_rac_bi_tu_choi() {
        assertRejected(verifier(false), "khong-phai-jwt");
        assertRejected(verifier(true), "khong-phai-jwt");
        assertRejected(verifier(false), "");
    }

    @Test
    @DisplayName("[java] không lấy được khoá công khai thì bị từ chối như token sai, không phải lỗi hệ thống")
    void khong_lay_duoc_khoa() {
        JWKSource<SecurityContext> broken = (selector, context) -> {
            throw new KeySourceException("Couldn't retrieve remote JWK set");
        };
        var v = new NimbusFirebaseVerifier(PROJECT, broken, false, CLOCK);
        assertThatThrownBy(() -> v.verify(signed(b -> {}))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[java] token không ký (alg=none) bị từ chối khi không phải emulator")
    void token_khong_ky_bi_tu_choi_khi_khong_phai_emulator() {
        assertRejected(verifier(false), unsigned(validClaims().build()));
    }

    @Test
    @DisplayName("[java] token không ký bị từ chối khi production dù có FIREBASE_AUTH_EMULATOR_HOST")
    void token_khong_ky_bi_tu_choi_khi_production() {
        var prod = new AppProperties(new MockEnvironment()
                .withProperty("NODE_ENV", "production")
                .withProperty("JWT_SECRET", "x".repeat(40))
                .withProperty("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9099"));
        assertThat(NimbusFirebaseVerifier.emulatorAllowed(prod)).isFalse();
        assertRejected(verifier(NimbusFirebaseVerifier.emulatorAllowed(prod)), unsigned(validClaims().build()));
    }

    @Test
    @DisplayName("[java] emulator chỉ bật khi có FIREBASE_AUTH_EMULATOR_HOST và không phải production")
    void emulator_chi_bat_khi_co_host_va_khong_production() {
        var dev = new AppProperties(new MockEnvironment().withProperty("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9099"));
        var devNoHost = new AppProperties(new MockEnvironment());
        assertThat(NimbusFirebaseVerifier.emulatorAllowed(dev)).isTrue();
        assertThat(NimbusFirebaseVerifier.emulatorAllowed(devNoHost)).isFalse();
    }

    @Test
    @DisplayName("[java] token không ký được nhận khi emulator, vẫn kiểm iss, aud, exp, sub")
    void token_khong_ky_duoc_nhan_khi_emulator() {
        FirebaseInfo info = verifier(true).verify(unsigned(validClaims().build()));
        assertThat(info.phone()).isEqualTo("+84911000077");
        assertThat(info.authTime()).isEqualTo(NOW.minusSeconds(120));

        assertRejected(verifier(true), unsigned(validClaims().audience("du-an-khac").build()));
        assertRejected(verifier(true), unsigned(validClaims().issuer("https://securetoken.google.com/du-an-khac").build()));
        assertRejected(verifier(true), unsigned(validClaims().expirationTime(Date.from(NOW.minusSeconds(1))).build()));
        assertRejected(verifier(true), unsigned(validClaims().subject("").build()));
    }
}
