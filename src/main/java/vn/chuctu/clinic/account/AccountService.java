package vn.chuctu.clinic.account;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.patient.Patient;
import vn.chuctu.clinic.patient.PatientForms.AccountData;
import vn.chuctu.clinic.patient.PatientService;

/** Tài khoản người dùng (account.service.js). Task 6 chỉ có phần tự sửa thông tin; quản lý tài khoản của Admin thêm ở Task 11. */
@Service
public class AccountService {

    /** Tài khoản sau khi sửa và hồ sơ chính chủ đã đồng bộ (null nếu chưa có hồ sơ). */
    public record MeUpdate(User user, Patient self) {}

    private final UserRepository users;
    private final PatientService patients;
    private final Clock clock;

    public AccountService(UserRepository users, PatientService patients, Clock clock) {
        this.users = users;
        this.patients = patients;
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
}
