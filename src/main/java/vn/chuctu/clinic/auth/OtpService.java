package vn.chuctu.clinic.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Hmac;
import vn.chuctu.clinic.common.Phones;
import vn.chuctu.clinic.common.Rules;

/**
 * Mã xác nhận 6 số gửi tới số điện thoại (issueCode, verifyCode, verifyProof của auth.service.js). Mã chỉ lưu dạng băm
 * HMAC-SHA256 khoá JWT_SECRET. Lớp này cố ý không dùng giao dịch bao ngoài: lần nhập sai phải được ghi lại dù request
 * sau đó trả lỗi.
 */
@Service
public class OtpService {

    /** Bằng chứng giữ số điện thoại: mã 6 số (server tự gửi) hoặc ID token Firebase. */
    public record Proof(String code, String idToken) {}

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String CODE_EXPIRED = "Mã xác nhận đã hết hạn. Cô chú bấm \"Gửi lại mã\" để nhận mã mới.";

    private final VerificationCodeRepository codes;
    private final SmsGateway sms;
    private final EmailGateway emailGateway;
    private final FirebaseVerifier firebase;
    private final AppProperties config;
    private final Clock clock;
    private final JsonMapper mapper;

    public OtpService(VerificationCodeRepository codes, SmsGateway sms, EmailGateway emailGateway, FirebaseVerifier firebase, AppProperties config, Clock clock, JsonMapper mapper) {
        this.codes = codes;
        this.sms = sms;
        this.emailGateway = emailGateway;
        this.firebase = firebase;
        this.config = config;
        this.clock = clock;
        this.mapper = mapper;
    }

    /** Băm mã gắn với số điện thoại và mục đích: hmac(JWT_SECRET, "phone:purpose:code"). */
    public String hashCode(String phone, String purpose, String code) {
        return Hmac.sha256Hex(config.jwtSecret, phone + ":" + purpose + ":" + code);
    }

    /**
     * Gửi mã xác nhận, chờ 60 giây giữa hai lần gửi cùng loại mã (BR.03). Giới hạn mỗi giờ tính riêng: tin nhắn 3 mã cho
     * mỗi số điện thoại, email 5 mã cho mỗi địa chỉ email. Mã luôn gắn với số điện thoại của tài khoản; có {@code email}
     * thì gửi mã tới email đó thay vì tin nhắn.
     */
    public Map<String, Object> issue(String phone, String purpose, JsonNode registerData, String email) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant since = now.minus(1, ChronoUnit.HOURS);
        List<VerificationCode> recent = email != null
                ? codes.findByEmailAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(email, since)
                : codes.findByPhoneAndEmailIsNullAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(phone, since);
        int maxPerHour = email != null ? Rules.OTP_EMAIL_MAX_PER_HOUR : Rules.OTP_MAX_PER_HOUR;

        for (VerificationCode c : recent) {
            if (!c.getPurpose().equals(purpose)) continue;
            long waitSec = ceilDiv(c.getCreatedAt().toEpochMilli() + Rules.OTP_RESEND_SECONDS * 1000L - now.toEpochMilli(), 1000);
            if (waitSec > 0) {
                throw Errors.tooMany("Cô chú vui lòng chờ " + waitSec + " giây rồi bấm gửi lại mã.", Map.of("retryAfter", waitSec));
            }
            break;
        }
        if (recent.size() >= maxPerHour) {
            long oldest = recent.get(recent.size() - 1).getCreatedAt().toEpochMilli();
            long waitMin = ceilDiv(oldest + 3_600_000L - now.toEpochMilli(), 60_000);
            throw Errors.tooMany((email != null ? "Email" : "Số điện thoại") + " này đã nhận đủ " + maxPerHour
                    + " mã trong một giờ. Cô chú vui lòng thử lại sau khoảng " + waitMin
                    + " phút hoặc gọi phòng khám để được giúp.", Map.of("retryAfter", waitMin * 60));
        }

        // Mã cũ chưa dùng của cùng mục đích hết hiệu lực khi có mã mới
        codes.expireOpenCodes(phone, purpose, now);

        // Firebase: trình duyệt tự gửi mã; ở đây chỉ ghi nhận lần xin mã (để giữ giới hạn và đối chiếu khi xác nhận)
        boolean firebase = email == null && sms.usesFirebase();
        String fixed = email != null ? emailGateway.devFixedCode() : null;
        String code = firebase ? null : fixed != null ? fixed : randomCode();
        VerificationCode record = new VerificationCode();
        record.setPhone(phone);
        record.setEmail(email);
        record.setPurpose(purpose);
        record.setCodeHash(firebase ? "firebase" : hashCode(phone, purpose, code));
        record.setRegisterData(registerData == null || registerData.isNull() ? null : mapper.convertValue(registerData, MAP));
        record.setExpiresAt(now.plus(Rules.OTP_TTL_MINUTES, ChronoUnit.MINUTES));
        record.setCreatedAt(now);
        record = codes.save(record);
        try {
            if (email != null) emailGateway.sendCode(email, code, purpose);
            else if (!firebase) sms.sendVerificationCode(phone, code, purpose);
        } catch (RuntimeException e) {
            // Gửi không được thì không tính lần xin mã này
            codes.deleteById(record.getId());
            throw e;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", true);
        out.put("provider", firebase ? "firebase" : "server");
        out.put("expiresInSeconds", Rules.OTP_TTL_MINUTES * 60);
        out.put("resendAfterSeconds", Rules.OTP_RESEND_SECONDS);
        if (email != null ? emailGateway.exposeCodeForDev() : sms.exposeCodeForDev()) out.put("devCode", code);
        return out;
    }

    /** Kiểm tra mã; mã đúng chỉ dùng được một lần. */
    public VerificationCode verify(String phone, String purpose, String code) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        VerificationCode doc = codes
                .findFirstByPhoneAndPurposeAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDescIdDesc(phone, purpose, now)
                .orElseThrow(() -> Errors.badRequest(CODE_EXPIRED, null, "CODE_EXPIRED"));
        // Giữ lượt trước rồi mới so mã: hết lượt thì khoá, kể cả khi nhiều yêu cầu dò mã đến cùng lúc
        if (doc.getAttempts() >= Rules.OTP_MAX_ATTEMPTS || codes.claimAttempt(doc.getId(), Rules.OTP_MAX_ATTEMPTS) == 0) {
            throw Errors.badRequest("Cô chú đã nhập sai quá nhiều lần. Vui lòng bấm \"Gửi lại mã\" để nhận mã mới.", null, "CODE_LOCKED");
        }

        byte[] expected = doc.getCodeHash().getBytes(StandardCharsets.UTF_8);
        byte[] actual = hashCode(phone, purpose, code).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            int left = Rules.OTP_MAX_ATTEMPTS - codes.attemptsOf(doc.getId());
            throw Errors.badRequest(
                    left > 0 ? "Mã xác nhận chưa đúng. Cô chú còn " + left + " lần nhập."
                            : "Mã xác nhận chưa đúng. Cô chú bấm \"Gửi lại mã\" để nhận mã mới.",
                    Map.of("fields", Map.of("code", "Mã xác nhận chưa đúng.")), "CODE_WRONG");
        }

        if (codes.markUsed(doc.getId(), now) == 0) {
            throw Errors.badRequest("Mã xác nhận đã được dùng. Cô chú bấm \"Gửi lại mã\".", null, "CODE_USED");
        }
        doc.setUsedAt(now);
        return doc;
    }

    /** Lần xin mã gần nhất của số điện thoại và mục đích (kể cả đã dùng hay hết hạn), null nếu chưa có. */
    public VerificationCode lastCode(String phone, String purpose) {
        return codes.findFirstByPhoneAndPurposeOrderByCreatedAtDescIdDesc(phone, purpose).orElse(null);
    }

    /** Bằng chứng giữ số điện thoại: Firebase (SMS_PROVIDER=firebase) hoặc mã 6 số do server gửi. */
    public VerificationCode verifyProof(String phone, String purpose, Proof proof) {
        if (sms.usesFirebase()) return verifyFirebase(phone, purpose, proof.idToken());
        if (proof.code() == null || proof.code().isEmpty()) {
            throw Errors.badRequest("Cô chú vui lòng nhập mã xác nhận.",
                    Map.of("fields", Map.of("code", "Mã xác nhận gồm 6 chữ số.")), "CODE_REQUIRED");
        }
        return verify(phone, purpose, proof.code());
    }

    /**
     * Firebase: kiểm ID token trình duyệt nhận được sau khi nhập đúng mã. Token phải đúng số điện thoại, vừa mới xác
     * thực, và có lần xin mã tương ứng còn hạn, chưa dùng (mỗi lần xin mã chỉ dùng được một lần).
     */
    private VerificationCode verifyFirebase(String phone, String purpose, String idToken) {
        if (idToken == null || idToken.isEmpty()) {
            throw Errors.badRequest("Cô chú vui lòng nhập mã xác nhận.",
                    Map.of("fields", Map.of("code", "Cô chú vui lòng nhập mã xác nhận.")), "CODE_REQUIRED");
        }
        FirebaseVerifier.FirebaseInfo info;
        try {
            info = firebase.verify(idToken);
        } catch (RuntimeException e) {
            // Token sai, hết hạn, hay không lấy được khoá công khai: người dùng đều chỉ cần xin mã lại. Chỉ ghi lý do, không ghi token.
            log.warn("Không xác nhận được ID token Firebase: {}", e.getMessage());
            throw Errors.badRequest("Phòng khám chưa xác nhận được mã. Cô chú bấm \"Gửi lại mã\" rồi thử lại.", null, "TOKEN_INVALID");
        }
        if (info.phone() == null || info.phone().isEmpty() || !phone.equals(Phones.normalize(info.phone()))) {
            throw Errors.badRequest("Mã xác nhận không thuộc số điện thoại này.", null, "PHONE_MISMATCH");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (info.authTime() == null
                || Duration.between(info.authTime(), now).toMillis() > (Rules.OTP_TTL_MINUTES + 1) * 60_000L) {
            throw Errors.badRequest(CODE_EXPIRED, null, "CODE_EXPIRED");
        }
        VerificationCode doc = codes
                .findFirstByPhoneAndPurposeAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDescIdDesc(phone, purpose, now)
                .orElseThrow(() -> Errors.badRequest(CODE_EXPIRED, null, "CODE_EXPIRED"));
        if (codes.markUsed(doc.getId(), now) == 0) throw Errors.badRequest(CODE_EXPIRED, null, "CODE_EXPIRED");
        doc.setUsedAt(now);
        return doc;
    }

    private static String randomCode() {
        return String.format("%0" + Rules.OTP_LENGTH + "d", RANDOM.nextInt((int) Math.pow(10, Rules.OTP_LENGTH)));
    }

    /** Math.ceil(a / b) của JavaScript cho số nguyên (kể cả âm). */
    private static long ceilDiv(long a, long b) {
        return Math.floorDiv(a + b - 1, b);
    }
}
