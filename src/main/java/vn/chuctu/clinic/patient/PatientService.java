package vn.chuctu.clinic.patient;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.chuctu.clinic.common.Counters;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Rules;
import vn.chuctu.clinic.common.VnTime;

/** Hồ sơ khám bệnh (patient.service.js). Task này chỉ có createPatient; các hàm còn lại thêm ở Task 6. */
@Service
public class PatientService {

    /** Thông tin tạo hồ sơ. Có dob thì birthYear lấy từ dob. */
    public record PatientInput(String fullName, LocalDate dob, Integer birthYear, String gender, String phone,
            String address, String nationalId, String email) {}

    private final PatientRepository patients;
    private final Counters counters;
    private final Clock clock;

    public PatientService(PatientRepository patients, Counters counters, Clock clock) {
        this.patients = patients;
        this.counters = counters;
        this.clock = clock;
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
}
