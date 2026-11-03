package vn.chuctu.clinic.patient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Counters;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Likes;
import vn.chuctu.clinic.common.Paging;
import vn.chuctu.clinic.common.Opt;
import vn.chuctu.clinic.common.Rules;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.patient.PatientForms.PatientData;

/** Hồ sơ khám bệnh (patient.service.js). */
@Service
public class PatientService {

    private static final String SELF = "BAN_THAN";

    /** Thông tin tạo hồ sơ. Có dob thì birthYear lấy từ dob. */
    public record PatientInput(String fullName, LocalDate dob, Integer birthYear, String gender, String phone,
            String address, String nationalId, String email) {}

    /** Kết quả người dùng sửa hồ sơ: user có giá trị khi đó là hồ sơ chính chủ nên tài khoản đổi theo. */
    public record OwnProfileUpdate(Patient patient, User user) {}

    /** Một trang kết quả tìm hồ sơ. */
    public record PatientPage(List<Patient> items, long total) {}

    private final PatientRepository patients;
    private final UserRepository users;
    private final PatientVisitQueries visits;
    private final Counters counters;
    private final Clock clock;

    public PatientService(PatientRepository patients, UserRepository users, PatientVisitQueries visits, Counters counters,
            Clock clock) {
        this.patients = patients;
        this.users = users;
        this.visits = visits;
        this.counters = counters;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** Mã bệnh nhân dạng CT-BN-26-0001, đánh số lại theo từng năm. */
    private String nextPatientCode() {
        String year = VnTime.yy(VnTime.today(clock.instant()));
        long seq = counters.next("patient-" + year);
        return "CT-BN-" + year + "-" + String.format("%04d", seq);
    }

    /** Tạo hồ sơ; userId có giá trị thì tính vào giới hạn 10 hồ sơ của tài khoản. */
    @Transactional
    public Patient createPatient(PatientInput data, Long userId, String relationship, Long createdBy) {
        if (userId != null && patients.countByUserId(userId) >= Rules.MAX_PROFILES_PER_ACCOUNT) {
            throw Errors.badRequest("Mỗi tài khoản lập được tối đa " + Rules.MAX_PROFILES_PER_ACCOUNT
                    + " hồ sơ khám. Cô chú xoá bớt hồ sơ không dùng nữa rồi thêm lại.", null, "PROFILE_LIMIT");
        }
        Patient patient = new Patient();
        patient.setPatientCode(nextPatientCode());
        patient.setUserId(userId);
        patient.setRelationship(relationship);
        patient.setFullName(data.fullName());
        patient.setDob(data.dob());
        patient.setBirthYear(data.dob() != null ? Integer.valueOf(data.dob().getYear()) : data.birthYear());
        patient.setGender(data.gender());
        patient.setPhone(data.phone());
        patient.setAddress(data.address());
        patient.setNationalId(data.nationalId());
        patient.setEmail(data.email());
        patient.setCreatedBy(createdBy);
        return patients.save(patient);
    }

    private static PatientInput input(PatientData data) {
        return new PatientInput(data.fullName().get(), data.dob().get(), data.birthYear().get(), data.gender().get(),
                data.phone().get(), data.address().get(), data.nationalId().get(), data.email().get());
    }

    /** Hồ sơ trả về giao diện (toPublic của bản Node): ngày sinh là nửa đêm UTC, năm sinh lấy từ ngày sinh nếu thiếu. */
    public Map<String, Object> toPublic(Patient patient) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(patient.getId()));
        out.put("patientCode", patient.getPatientCode());
        out.put("userId", patient.getUserId() == null ? null : String.valueOf(patient.getUserId()));
        out.put("relationship", patient.getRelationship());
        out.put("fullName", patient.getFullName());
        out.put("dob", midnightUtc(patient.getDob()));
        out.put("birthYear", patient.getBirthYear() != null ? patient.getBirthYear()
                : patient.getDob() != null ? Integer.valueOf(patient.getDob().getYear()) : null);
        out.put("gender", patient.getGender());
        out.put("phone", patient.getPhone());
        out.put("address", patient.getAddress());
        out.put("nationalId", patient.getNationalId());
        out.put("email", patient.getEmail());
        out.put("isLocked", patient.isLocked());
        out.put("lockedAt", patient.getLockedAt());
        out.put("createdAt", patient.getCreatedAt());
        out.put("updatedAt", patient.getUpdatedAt());
        return out;
    }

    /** Ngày (không giờ) thành thời điểm nửa đêm UTC, như Date của Mongo; null giữ nguyên. */
    public static Instant midnightUtc(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static boolean has(String value) {
        return value != null && !value.isEmpty();
    }

    private static <T> void ifPresent(Opt<T> value, java.util.function.Consumer<T> setter) {
        if (value.isPresent()) setter.accept(value.get());
    }

    private void applyUpdate(Patient patient, PatientData data) {
        ifPresent(data.fullName(), patient::setFullName);
        ifPresent(data.gender(), patient::setGender);
        ifPresent(data.phone(), patient::setPhone);
        ifPresent(data.address(), patient::setAddress);
        ifPresent(data.relationship(), patient::setRelationship);
        ifPresent(data.nationalId(), patient::setNationalId);
        ifPresent(data.email(), patient::setEmail);
        if (data.dob().isPresent()) {
            patient.setDob(data.dob().get());
            if (patient.getDob() != null) patient.setBirthYear(patient.getDob().getYear());
        }
        boolean hasDob = data.dob().isPresent() && data.dob().get() != null;
        if (data.birthYear().isPresent() && !hasDob) {
            Integer year = data.birthYear().get();
            if (patient.getDob() == null) {
                patient.setBirthYear(year);
            } else if (!Objects.equals(year, patient.getDob().getYear())) {
                throw Errors.badRequest("Hồ sơ đã có ngày sinh nên năm sinh đi theo ngày sinh.",
                        Map.of("fields", Map.of("birthYear", "Sửa ngày sinh để đổi năm sinh.")), "BAD_REQUEST");
            }
        }
    }

    // ---------- Hồ sơ chính chủ và tài khoản là một người: sửa bên nào, bên kia đổi theo ----------

    /**
     * Người sửa đang xem bản cũ thì từ chối, để không đè mất thay đổi vừa có ở nơi khác. So theo mili giây;
     * không gửi mốc thì không kiểm.
     */
    public static void assertFresh(Instant current, String expected) {
        if (expected == null || expected.isEmpty()) return;
        Long expectedMillis = Stamp.epochMillis(expected);
        if (expectedMillis == null || expectedMillis != current.toEpochMilli()) {
            throw Errors.conflict("Thông tin vừa được thay đổi ở nơi khác. Biểu mẫu đã cập nhật thông tin mới, vui lòng xem lại rồi lưu.",
                    "STALE");
        }
    }

    /**
     * Tài khoản đổi thông tin thì hồ sơ chính chủ đổi theo. Hồ sơ đang khoá chỉ nhận số điện thoại,
     * vì đó là số đăng nhập; các ô còn lại cập nhật lúc mở khoá. Trả null nếu tài khoản chưa có hồ sơ chính chủ.
     */
    @Transactional
    public Patient syncSelfProfile(User user) {
        Patient self = patients.findFirstByUserIdAndRelationshipOrderByIdAsc(user.getId(), SELF).orElse(null);
        if (self == null) return null;
        self.setPhone(user.getPhone());
        if (!self.isLocked()) {
            if (has(user.getFullName())) self.setFullName(user.getFullName());
            if (user.getDob() != null) self.setDob(user.getDob());
            if (has(user.getGender())) self.setGender(user.getGender());
            if (has(user.getAddress())) self.setAddress(user.getAddress());
            if (user.getDob() != null) self.setBirthYear(user.getDob().getYear());
        }
        return patients.saveAndFlush(self);
    }

    /** Hồ sơ chính chủ đổi thông tin thì tài khoản đổi theo. Số điện thoại là tên đăng nhập nên không đổi từ hồ sơ. */
    private User syncAccount(Patient patient) {
        if (!isAccountProfile(patient)) return null;
        User user = users.findById(patient.getUserId()).orElse(null);
        if (user == null) return null;
        if (has(patient.getFullName())) user.setFullName(patient.getFullName());
        if (patient.getDob() != null) user.setDob(patient.getDob());
        if (has(patient.getGender())) user.setGender(patient.getGender());
        if (has(patient.getAddress())) user.setAddress(patient.getAddress());
        user.setInfoUpdatedAt(now());
        return users.saveAndFlush(user);
    }

    private static boolean isAccountProfile(Patient patient) {
        return SELF.equals(patient.getRelationship()) && patient.getUserId() != null;
    }

    private static boolean changesLoginPhone(Patient patient, PatientData data) {
        return isAccountProfile(patient) && data.phone().isPresent() && !Objects.equals(data.phone().get(), patient.getPhone());
    }

    /** Hồ sơ chính chủ không xoá trắng được thông tin đã có, nếu không sẽ lệch với tài khoản. */
    private static void assertKeepsShared(Patient patient, PatientData data) {
        if (!isAccountProfile(patient)) return;
        Map<String, Object> fields = new LinkedHashMap<>();
        if (clears(data.dob()) && patient.getDob() != null) fields.put("dob", "Không xoá được ngày sinh của hồ sơ chính chủ.");
        if (clears(data.gender()) && has(patient.getGender())) fields.put("gender", "Không xoá được giới tính của hồ sơ chính chủ.");
        if (clears(data.address()) && has(patient.getAddress())) fields.put("address", "Không xoá được địa chỉ của hồ sơ chính chủ.");
        if (!fields.isEmpty()) {
            throw Errors.badRequest("Hồ sơ chính chủ phải giữ đủ thông tin của tài khoản.", Map.of("fields", fields), "BAD_REQUEST");
        }
    }

    private static boolean clears(Opt<?> value) {
        return value.isPresent() && value.get() == null;
    }

    // ---------- Hồ sơ khám của người dùng (bản thân và người thân) ----------

    @Transactional(readOnly = true)
    public List<Patient> listOwnProfiles(long userId) {
        return patients.findByUserIdOrderByRelationshipAscCreatedAtAscIdAsc(userId);
    }

    /** Ngày khám gần nhất (phiếu đã hoàn thành) của từng hồ sơ. */
    @Transactional(readOnly = true)
    public Map<Long, LocalDate> lastVisitDates(List<Long> patientIds) {
        return visits.lastCompletedSlotDates(patientIds);
    }

    /** Ngày khám gần nhất theo bệnh án của từng hồ sơ, cho danh sách của tiếp tân. */
    @Transactional(readOnly = true)
    public Map<Long, LocalDate> lastRecordDates(List<Long> patientIds) {
        return visits.lastRecordVisitDates(patientIds);
    }

    public boolean hasOwnSelfProfile(long userId) {
        return patients.existsByUserIdAndRelationship(userId, SELF);
    }

    public boolean hasOtherSelfProfile(long userId, long exceptPatientId) {
        return patients.existsByUserIdAndRelationshipAndIdNot(userId, SELF, exceptPatientId);
    }

    /** Hồ sơ chính chủ của tài khoản, null nếu chưa có. */
    @Transactional(readOnly = true)
    public Patient findSelfProfile(long userId) {
        return patients.findFirstByUserIdAndRelationshipOrderByIdAsc(userId, SELF).orElse(null);
    }

    @Transactional(readOnly = true)
    public Patient getOwnProfile(long userId, long patientId) {
        return patients.findByIdAndUserId(patientId, userId).orElseThrow(() -> Errors.notFound("Không tìm thấy hồ sơ khám."));
    }

    @Transactional
    public OwnProfileUpdate updateOwnProfile(long userId, long patientId, PatientData data) {
        Patient patient = getOwnProfile(userId, patientId);
        if (patient.isLocked()) {
            throw Errors.forbidden("Hồ sơ này đang bị phòng khám khoá nên không sửa được. Cô chú gọi phòng khám để được hỗ trợ.");
        }
        if (SELF.equals(patient.getRelationship()) && data.relationship().isPresent() && data.relationship().get() != null
                && !SELF.equals(data.relationship().get())) {
            throw Errors.badRequest("Không đổi quan hệ của hồ sơ chính chủ.");
        }
        if (changesLoginPhone(patient, data)) {
            throw Errors.badRequest("Số điện thoại là tên đăng nhập nên không tự sửa được. Cô chú gọi phòng khám nếu cần đổi.",
                    Map.of("fields", Map.of("phone", "Số đăng nhập của tài khoản, không tự sửa được.")), "BAD_REQUEST");
        }
        assertKeepsShared(patient, data);
        assertFresh(patient.getUpdatedAt(), data.expectedUpdatedAt());
        applyUpdate(patient, data);
        patients.saveAndFlush(patient);
        return new OwnProfileUpdate(patient, syncAccount(patient));
    }

    /**
     * Xoá hồ sơ khỏi danh sách của tài khoản. Hồ sơ đã có lượt khám không bị xoá cứng (BR.38):
     * hồ sơ được tách khỏi tài khoản và phòng khám vẫn giữ lịch sử.
     */
    @Transactional
    public Map<String, Object> deleteOwnProfile(long userId, long patientId) {
        Patient patient = getOwnProfile(userId, patientId);
        if (SELF.equals(patient.getRelationship())) throw Errors.badRequest("Không xoá được hồ sơ của chính chủ tài khoản.");
        if (visits.hasActiveAppointment(patient.getId())) {
            throw Errors.conflict("Hồ sơ này còn phiếu khám chưa hoàn thành. Cô chú huỷ phiếu khám trước rồi xoá hồ sơ.",
                    "PROFILE_IN_USE");
        }
        if (visits.hasHistory(patient.getId())) {
            patient.setUserId(null);
            patients.saveAndFlush(patient);
            return Map.of("detached", true);
        }
        patients.delete(patient);
        patients.flush();
        return Map.of("deleted", true);
    }

    // ---------- Quản lý bệnh nhân (Tiếp tân, Admin) ----------

    /** Tìm theo họ tên, mã hoặc số điện thoại, mới nhất trước. {@code locked} là "true" hoặc "false", khác thì không lọc. */
    @Transactional(readOnly = true)
    public PatientPage searchPatients(String q, String locked, Paging paging) {
        String pattern = q == null || q.isEmpty() ? null : Likes.contains(Body.jsTrim(q));
        Boolean lockedFilter = "true".equals(locked) ? Boolean.TRUE : "false".equals(locked) ? Boolean.FALSE : null;
        Sort order = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        // Vị trí bắt đầu vượt int thì chắc chắn không còn dòng nào, nhưng vẫn cần tổng số
        boolean beyondEnd = paging.offset() > Integer.MAX_VALUE;
        Page<Patient> result = patients.search(pattern, lockedFilter,
                beyondEnd ? PageRequest.of(0, 1, order) : PageRequest.of(paging.page() - 1, paging.limit(), order));
        return new PatientPage(beyondEnd ? List.of() : result.getContent(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<Patient> findDuplicatesByPhone(String phone, Long excludeId) {
        if (phone == null || phone.isEmpty()) return List.of();
        return excludeId == null ? patients.findTop10ByPhoneOrderByIdAsc(phone)
                : patients.findTop10ByPhoneAndIdNotOrderByIdAsc(phone, excludeId);
    }

    /** Tiếp tân tạo hồ sơ: cảnh báo trùng số điện thoại trước khi lưu (BR.25, BR.35). */
    @Transactional
    public Patient staffCreatePatient(PatientData data, User staff, boolean confirmDuplicate) {
        if (!confirmDuplicate) {
            List<Patient> duplicates = findDuplicatesByPhone(data.phone().get(), null);
            if (!duplicates.isEmpty()) {
                throw Errors.conflict("Đã có hồ sơ dùng số điện thoại này. Kiểm tra lại trước khi tạo hồ sơ mới.", "DUPLICATE_PHONE",
                        Map.of("duplicates", duplicates.stream().map(this::toPublic).collect(Collectors.toList())));
            }
        }
        return createPatient(input(data), null, null, staff.getId());
    }

    @Transactional
    public Patient createOwnProfile(PatientData data, User user) {
        return createPatient(input(data), user.getId(), data.relationship().get(), user.getId());
    }

    @Transactional(readOnly = true)
    public Patient getPatient(long patientId) {
        return patients.findById(patientId).orElseThrow(() -> Errors.notFound("Không tìm thấy hồ sơ bệnh nhân."));
    }

    @Transactional
    public Patient staffUpdatePatient(long patientId, PatientData data) {
        Patient patient = getPatient(patientId);
        if (patient.isLocked()) throw Errors.forbidden("Hồ sơ đang bị khoá nên không sửa được thông tin.");
        if (changesLoginPhone(patient, data)) {
            throw Errors.badRequest("Đây là số đăng nhập của tài khoản. Admin đổi số ở Quản lý tài khoản.",
                    Map.of("fields", Map.of("phone", "Số đăng nhập của tài khoản, đổi ở Quản lý tài khoản.")), "BAD_REQUEST");
        }
        assertKeepsShared(patient, data);
        assertFresh(patient.getUpdatedAt(), data.expectedUpdatedAt());
        applyUpdate(patient, data);
        patients.saveAndFlush(patient);
        syncAccount(patient);
        return patient;
    }

    /** Khoá hoặc mở khoá hồ sơ; mở khoá thì hồ sơ chính chủ nhận lại các thay đổi của tài khoản trong lúc bị khoá. */
    @Transactional
    public Patient setPatientLock(long patientId, boolean locked) {
        Patient patient = getPatient(patientId);
        patient.setLocked(locked);
        patient.setLockedAt(locked ? now() : null);
        patients.saveAndFlush(patient);
        if (!locked && isAccountProfile(patient)) {
            User user = users.findById(patient.getUserId()).orElse(null);
            if (user != null) return syncSelfProfile(user);
        }
        return patient;
    }
}
