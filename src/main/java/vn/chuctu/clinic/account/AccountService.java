package vn.chuctu.clinic.account;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.chuctu.clinic.account.AccountForms.CreateData;
import vn.chuctu.clinic.account.AccountForms.UpdateData;
import vn.chuctu.clinic.auth.AuthService;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Constants;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Likes;
import vn.chuctu.clinic.common.Opt;
import vn.chuctu.clinic.common.Paging;
import vn.chuctu.clinic.common.Rules;
import vn.chuctu.clinic.patient.Patient;
import vn.chuctu.clinic.patient.PatientForms.AccountData;
import vn.chuctu.clinic.patient.PatientService;
import vn.chuctu.clinic.patient.PatientService.PatientInput;

/**
 * Tài khoản người dùng (account.service.js): người dùng tự sửa thông tin (UC.1.5) và Admin quản lý tài khoản (UC.8.1 đến
 * UC.8.5). Mọi thay đổi chỉ ghi các cột đã đổi (UPDATE có chọn cột), không ghi lại cả dòng, để không đè lần đăng nhập hay
 * thông tin người dùng vừa sửa ở nơi khác. Bước băm mật khẩu chậm làm trước khi mở giao dịch.
 */
@Service
public class AccountService {

    /** Tài khoản sau khi sửa và hồ sơ chính chủ đã đồng bộ (null nếu chưa có hồ sơ). */
    public record MeUpdate(User user, Patient self) {}

    /** Một trang danh sách tài khoản kèm số tài khoản của từng vai trò (tính trên toàn bộ, không theo bộ lọc). */
    public record AccountPage(List<User> items, long total, Map<String, Long> roleCounts) {}

    private static final String NOT_FOUND = "Không tìm thấy tài khoản.";
    private static final String PHONE_TAKEN = "Số điện thoại đã được dùng cho tài khoản khác.";
    private static final String EMAIL_TAKEN = "Email đã được dùng cho tài khoản khác.";
    private static final String TEMP_PASSWORD_MIN = "Mật khẩu tạm cần tối thiểu " + Rules.MIN_PASSWORD_LENGTH + " ký tự.";

    private final UserRepository users;
    private final PatientService patients;
    private final AuthService auth;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AccountService(UserRepository users, PatientService patients, AuthService auth, TransactionTemplate tx, Clock clock) {
        this.users = users;
        this.patients = patients;
        this.auth = auth;
        this.tx = tx;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * Người dùng tự sửa họ tên, ngày sinh, giới tính, địa chỉ (UC.1.5); hồ sơ chính chủ đổi theo. Đọc lại tài khoản trong
     * giao dịch để chỉ ghi các cột đã đổi, không đè lần đăng nhập vừa có.
     */
    @Transactional
    public MeUpdate updateMe(User current, AccountData data) {
        User user = users.findById(current.getId()).orElseThrow(Errors::unauthorized);
        PatientService.assertFresh(user.getInfoUpdatedAt() != null ? user.getInfoUpdatedAt() : user.getCreatedAt(),
                data.expectedUpdatedAt());
        if (data.fullName().isPresent()) user.setFullName(data.fullName().get());
        if (data.gender().isPresent()) user.setGender(data.gender().get());
        if (data.address().isPresent()) user.setAddress(data.address().get());
        if (data.dob().isPresent()) user.setDob(data.dob().get());
        user.setInfoUpdatedAt(now());
        users.saveAndFlush(user);
        return new MeUpdate(user, patients.syncSelfProfile(user));
    }

    // ---------- Quản lý tài khoản của Admin ----------

    /** Số điện thoại và email (nếu có) chưa thuộc tài khoản nào khác {@code excludeId}. */
    private void assertUnique(String phone, String email, Long excludeId) {
        if (phone != null && (excludeId == null ? users.existsByPhone(phone) : users.existsByPhoneAndIdNot(phone, excludeId))) {
            throw Errors.conflict(PHONE_TAKEN, "PHONE_TAKEN", Map.of("fields", Map.of("phone", PHONE_TAKEN)));
        }
        if (email != null && (excludeId == null ? users.findByEmail(email).isPresent() : users.existsByEmailAndIdNot(email, excludeId))) {
            throw Errors.conflict(EMAIL_TAKEN, "EMAIL_TAKEN", Map.of("fields", Map.of("email", EMAIL_TAKEN)));
        }
    }

    /** Tài khoản nội bộ phải có email: Quên mật khẩu gửi mã qua email. */
    private static RuntimeException emailRequired() {
        return Errors.badRequest("Tài khoản nội bộ cần có email để dùng chức năng Quên mật khẩu.",
                Map.of("fields", Map.of("email", "Vui lòng nhập email.")), "EMAIL_REQUIRED");
    }

    private static boolean has(String s) {
        return s != null && !s.isEmpty();
    }

    /** Hai yêu cầu cùng lúc chọn một số hay email: khoá duy nhất của database chặn, báo như lúc kiểm tra trước. */
    private RuntimeException clash(DataIntegrityViolationException e, String phone, String email, Long excludeId) {
        assertUnique(phone, email, excludeId);
        return e;
    }

    public AccountPage listAccounts(String q, String role, String status, Paging paging) {
        String pattern = q == null || q.isEmpty() ? null : Likes.contains(Body.jsTrim(q));
        String roleFilter = role != null && Constants.ROLES.contains(role) ? role : null;
        Boolean active = "active".equals(status) ? Boolean.TRUE : "locked".equals(status) ? Boolean.FALSE : null;
        // Vị trí bắt đầu vượt int thì chắc chắn không còn dòng nào, nhưng vẫn cần tổng số
        var page = paging.offset() > Integer.MAX_VALUE
                ? users.search(pattern, roleFilter, active, PageRequest.of(0, 1, SORT))
                : users.search(pattern, roleFilter, active, PageRequest.of(paging.page() - 1, paging.limit(), SORT));
        List<User> items = paging.offset() > Integer.MAX_VALUE ? List.of() : page.getContent();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String r : Constants.ROLES) counts.put(r, 0L);
        for (Object[] row : users.roleCounts()) counts.put((String) row[0], ((Number) row[1]).longValue());
        return new AccountPage(items, page.getTotalElements(), counts);
    }

    private static final Sort SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    public User getAccount(long id) {
        return users.findById(id).orElseThrow(() -> Errors.notFound(NOT_FOUND));
    }

    /** Admin thêm tài khoản; tài khoản nội bộ phải có email và mật khẩu tạm tối thiểu 8 ký tự (BR.33). */
    public User createAccount(CreateData data) {
        String email = data.email().get();
        assertUnique(data.phone(), email, null);
        boolean internal = Constants.STAFF_ROLES.contains(data.role());
        if (internal && !has(email)) throw emailRequired();
        if (internal && (data.tempPassword() == null || data.tempPassword().length() < Rules.MIN_PASSWORD_LENGTH)) {
            throw Errors.badRequest(TEMP_PASSWORD_MIN, Map.of("fields", Map.of("tempPassword", TEMP_PASSWORD_MIN)), "BAD_REQUEST");
        }
        String hash = internal ? auth.hashPassword(data.tempPassword()) : null;
        try {
            return tx.execute(status -> {
                User user = new User();
                user.setPhone(data.phone());
                user.setEmail(has(email) ? email : null);
                user.setFullName(data.fullName());
                user.setRole(data.role());
                user.setPasswordHash(hash);
                user.setMustChangePassword(internal);
                user = users.save(user);
                if ("USER".equals(user.getRole())) {
                    patients.createPatient(new PatientInput(user.getFullName(), null, null, null, user.getPhone(), null, null, null),
                            user.getId(), "BAN_THAN", user.getId());
                }
                return user;
            });
        } catch (DataIntegrityViolationException e) {
            throw clash(e, data.phone(), email, null);
        }
    }

    /** Sửa thông tin; đọc lại và so mốc thời gian trong giao dịch, chỉ ghi các cột đã đổi, rồi đồng bộ hồ sơ chính chủ. */
    public User updateAccount(long id, UpdateData data) {
        String phone = data.phone().get();
        String email = data.email().get();
        try {
            return tx.execute(status -> {
                User user = getAccount(id);
                PatientService.assertFresh(user.getInfoUpdatedAt() != null ? user.getInfoUpdatedAt() : user.getCreatedAt(),
                        data.expectedUpdatedAt());
                if (user.isStaff() && data.email().isPresent() && email == null) throw emailRequired();
                assertUnique(phone, email, user.getId());
                if (data.fullName().isPresent()) user.setFullName(data.fullName().get());
                if (data.phone().isPresent()) user.setPhone(phone);
                if (data.address().isPresent()) user.setAddress(data.address().get());
                if (data.email().isPresent()) user.setEmail(email);
                user.setInfoUpdatedAt(now());
                users.saveAndFlush(user);
                patients.syncSelfProfile(user);
                return user;
            });
        } catch (DataIntegrityViolationException e) {
            throw clash(e, phone, email, id);
        }
    }

    /** Khoá tài khoản: không đăng nhập được nữa, phiếu khám đã đặt giữ nguyên (BR.33). */
    public User setAccountActive(User admin, long id, boolean active) {
        User user = getAccount(id);
        if (user.getId().equals(admin.getId())) throw Errors.badRequest("Không tự khoá tài khoản đang đăng nhập.");
        users.setActive(id, active, now());
        return getAccount(id);
    }

    /** Đổi vai trò. Lên vai trò nội bộ mà chưa có mật khẩu, email thì cần đặt mật khẩu tạm và nhập email (BR.32). */
    public User setRole(User admin, long id, String role, String tempPassword, Opt<String> emailInput) {
        User user = getAccount(id);
        if (user.getId().equals(admin.getId()) && !"ADMIN".equals(role)) {
            throw Errors.badRequest("Không tự hạ quyền tài khoản đang đăng nhập.");
        }
        boolean internal = Constants.STAFF_ROLES.contains(role);
        boolean needsPassword = internal && user.getPasswordHash() == null;
        boolean needsEmail = internal && !has(user.getEmail());
        String email = emailInput.get();
        if (needsPassword && (tempPassword == null || tempPassword.length() < Rules.MIN_PASSWORD_LENGTH)) {
            String message = "Tài khoản nội bộ cần mật khẩu tạm tối thiểu " + Rules.MIN_PASSWORD_LENGTH + " ký tự.";
            throw Errors.badRequest(message,
                    Map.of("fields", Map.of("tempPassword", "Cần mật khẩu tạm tối thiểu " + Rules.MIN_PASSWORD_LENGTH + " ký tự.")),
                    "PASSWORD_REQUIRED");
        }
        if (needsEmail) {
            if (!has(email)) throw emailRequired();
            assertUnique(null, email, user.getId());
        }
        String hash = needsPassword ? auth.hashPassword(tempPassword) : null;
        try {
            return tx.execute(status -> {
                User fresh = getAccount(id);
                if (hash != null && fresh.getPasswordHash() == null) {
                    fresh.setPasswordHash(hash);
                    fresh.setMustChangePassword(true);
                }
                if (needsEmail && !has(fresh.getEmail())) fresh.setEmail(email);
                fresh.setRole(role);
                User saved = users.saveAndFlush(fresh);
                // Tài khoản nội bộ không có hồ sơ khám; chuyển sang Người dùng thì phải có hồ sơ chính chủ như tài khoản mới
                if ("USER".equals(role)) patients.ensureSelfProfile(saved);
                return saved;
            });
        } catch (DataIntegrityViolationException e) {
            throw clash(e, null, needsEmail ? email : null, id);
        }
    }

    /** Cấp mật khẩu tạm khi không tự lấy lại được qua email. Tự đổi mật khẩu của mình thì ở trang Tài khoản. */
    public User resetPassword(User admin, long id, String tempPassword) {
        User user = getAccount(id);
        if (user.getId().equals(admin.getId())) {
            throw Errors.badRequest("Không tự đặt lại mật khẩu của tài khoản đang đăng nhập.");
        }
        if (!user.isStaff()) throw Errors.badRequest("Tài khoản người dùng không dùng mật khẩu.");
        users.setTempPassword(id, auth.hashPassword(tempPassword), now());
        return getAccount(id);
    }
}
