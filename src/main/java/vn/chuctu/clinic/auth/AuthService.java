package vn.chuctu.clinic.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;
import vn.chuctu.clinic.auth.OtpService.Proof;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Hmac;
import vn.chuctu.clinic.patient.PatientService;
import vn.chuctu.clinic.patient.PatientService.PatientInput;

/**
 * Đăng ký, đăng nhập không mật khẩu bằng mã tin nhắn (người dùng) và đăng nhập bằng mật khẩu (tiếp tân, quản trị).
 * Phần quên mật khẩu và đổi email thêm ở Task 4, kiểm token Firebase ở Task 5.
 */
@Service
public class AuthService {

    /** Thông tin đăng ký đã kiểm tra, giữ cùng mã xác nhận cho tới khi người dùng nhập đúng mã. */
    public record RegisterData(String fullName, String phone, LocalDate dob, String gender, String address) {}

    /** Kết quả bước đầu của đăng nhập: vào luôn (user) hoặc phải nhập mã (otp). */
    public record LoginStart(User user, boolean needCode, Map<String, Object> otp) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern IDENTIFIER_SEPARATORS = Pattern.compile("(?U)[\\s.\\-]");
    /** bcrypt chỉ đọc 72 byte đầu của mật khẩu; bcryptjs bỏ phần dư, Spring thì báo lỗi nên cắt trước. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final TrustedDeviceRepository devices;
    private final OtpService otp;
    private final PatientService patients;
    private final AppProperties config;
    private final Clock clock;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);

    public AuthService(UserRepository users, TrustedDeviceRepository devices, OtpService otp, PatientService patients,
            AppProperties config, Clock clock, JsonMapper mapper, TransactionTemplate tx) {
        this.users = users;
        this.devices = devices;
        this.otp = otp;
        this.patients = patients;
        this.config = config;
        this.clock = clock;
        this.mapper = mapper;
        this.tx = tx;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** hmac(JWT_SECRET, "device:" + deviceId): mã thiết bị trong cookie không lưu thẳng. */
    public String hashDevice(String deviceId) {
        return Hmac.sha256Hex(config.jwtSecret, "device:" + deviceId);
    }

    /** 32 byte ngẫu nhiên, base64url. */
    public String newDeviceId() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---------- Người dùng: đăng ký ----------

    public Map<String, Object> startRegister(RegisterData data) {
        if (users.existsByPhone(data.phone())) throw phoneTaken(true);
        ObjectNode registerData = mapper.createObjectNode();
        registerData.put("fullName", data.fullName());
        registerData.put("phone", data.phone());
        registerData.put("dob", data.dob().toString());
        registerData.put("gender", data.gender());
        registerData.put("address", data.address());
        return otp.issue(data.phone(), "REGISTER", registerData, null);
    }

    /**
     * Mã đúng thì tạo tài khoản cùng hồ sơ khám của chính chủ. Kiểm mã chạy ngoài giao dịch (lần nhập sai phải được ghi
     * lại dù request lỗi); chỉ việc tạo tài khoản và hồ sơ nằm trong một giao dịch, hỏng một thì không còn gì.
     */
    public User completeRegister(String phone, Proof proof) {
        VerificationCode doc = otp.verifyProof(phone, "REGISTER", proof);
        Map<String, Object> data = doc.getRegisterData();
        if (data == null) throw Errors.badRequest("Không tìm thấy thông tin đăng ký. Cô chú vui lòng đăng ký lại.");
        if (users.existsByPhone(phone)) throw phoneTaken(false);

        return tx.execute(status -> {
            User user = new User();
            user.setPhone(phone);
            user.setFullName((String) data.get("fullName"));
            user.setDob(data.get("dob") == null ? null : LocalDate.parse((String) data.get("dob")));
            user.setGender((String) data.get("gender"));
            user.setAddress((String) data.get("address"));
            user.setRole("USER");
            user.setLastLoginAt(now());
            user = users.save(user);
            // Hồ sơ khám của chính chủ tài khoản được tạo sẵn
            patients.createPatient(new PatientInput(user.getFullName(), user.getDob(), null, user.getGender(),
                    user.getPhone(), user.getAddress(), null, null), user.getId(), "BAN_THAN", user.getId());
            return user;
        });
    }

    private static RuntimeException phoneTaken(boolean withFields) {
        return withFields
                ? Errors.conflict("Số điện thoại này đã có tài khoản. Cô chú bấm \"Đăng nhập\" để vào tài khoản.", "PHONE_TAKEN",
                        Map.of("fields", Map.of("phone", "Số điện thoại này đã có tài khoản.")))
                : Errors.conflict("Số điện thoại này đã có tài khoản.", "PHONE_TAKEN");
    }

    // ---------- Người dùng: đăng nhập không mật khẩu ----------

    private boolean isTrusted(Long userId, String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return false;
        return devices.touch(userId, hashDevice(deviceId), now()) > 0;
    }

    public void trustDevice(Long userId, String deviceId) {
        devices.upsert(userId, hashDevice(deviceId), now());
    }

    private User findLoginUser(String phone) {
        User user = users.findByPhone(phone).orElse(null);
        if (user == null || !user.getRole().equals("USER")) {
            throw Errors.notFound("Số điện thoại này chưa có tài khoản. Cô chú bấm \"Tạo tài khoản\" để đăng ký.");
        }
        if (!user.isActive()) {
            throw Errors.forbidden("Tài khoản đang bị khoá. Cô chú vui lòng gọi phòng khám để được hỗ trợ.");
        }
        return user;
    }

    /** Thiết bị đã xác nhận thì đăng nhập ngay; thiết bị mới phải nhập mã (BR.02). */
    public LoginStart startLogin(String phone, String deviceId) {
        User user = findLoginUser(phone);
        if (isTrusted(user.getId(), deviceId)) {
            user.setLastLoginAt(now());
            return new LoginStart(users.save(user), false, null);
        }
        return new LoginStart(null, true, otp.issue(phone, "NEW_DEVICE", null, null));
    }

    public User completeLogin(String phone, Proof proof) {
        User user = findLoginUser(phone);
        otp.verifyProof(phone, "NEW_DEVICE", proof);
        user.setLastLoginAt(now());
        return users.save(user);
    }

    public Map<String, Object> resendCode(String phone, String purpose) {
        if (purpose.equals("REGISTER")) {
            VerificationCode last = otp.lastCode(phone, "REGISTER");
            if (last == null || last.getRegisterData() == null) {
                throw Errors.badRequest("Cô chú vui lòng điền lại thông tin đăng ký.");
            }
            if (users.existsByPhone(phone)) throw phoneTaken(false);
            return otp.issue(phone, "REGISTER", mapper.valueToTree(last.getRegisterData()), null);
        }
        findLoginUser(phone);
        return otp.issue(phone, "NEW_DEVICE", null, null);
    }

    // ---------- Nội bộ: Tiếp tân, Admin ----------

    public User staffLogin(String identifier, String password) {
        String id = identifier.strip();
        User user = (id.contains("@") ? users.findByEmail(id.toLowerCase())
                : users.findByPhone(IDENTIFIER_SEPARATORS.matcher(id).replaceAll(""))).orElse(null);
        if (user == null || user.getPasswordHash() == null || !user.isStaff() || !matches(password, user.getPasswordHash())) {
            throw Errors.unauthorized("Số điện thoại/email hoặc mật khẩu chưa đúng.");
        }
        if (!user.isActive()) throw Errors.forbidden("Tài khoản đang bị khoá. Vui lòng liên hệ quản trị viên.");
        user.setLastLoginAt(now());
        return users.save(user);
    }

    public User changePassword(Long userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElse(null);
        if (user == null || user.getPasswordHash() == null) throw Errors.badRequest("Tài khoản này không dùng mật khẩu.");
        if (!matches(currentPassword, user.getPasswordHash())) {
            throw Errors.badRequest("Mật khẩu hiện tại chưa đúng.",
                    Map.of("fields", Map.of("currentPassword", "Mật khẩu hiện tại chưa đúng.")), "BAD_REQUEST");
        }
        if (currentPassword.equals(newPassword)) {
            throw Errors.badRequest("Mật khẩu mới phải khác mật khẩu hiện tại.",
                    Map.of("fields", Map.of("newPassword", "Mật khẩu mới phải khác mật khẩu hiện tại.")), "BAD_REQUEST");
        }
        user.setPasswordHash(hashPassword(newPassword));
        user.setMustChangePassword(false);
        return users.save(user);
    }

    public String hashPassword(String password) {
        return bcrypt.encode(bcryptInput(password));
    }

    private boolean matches(String password, String hash) {
        return bcrypt.matches(bcryptInput(password), hash);
    }

    private static String bcryptInput(String password) {
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= BCRYPT_MAX_BYTES) return password;
        // Cắt ở ranh giới ký tự để chuỗi vẫn là UTF-8 hợp lệ; phần cắt đi bcrypt cũng không đọc
        int end = BCRYPT_MAX_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return new String(Arrays.copyOf(bytes, end), StandardCharsets.UTF_8);
    }
}
