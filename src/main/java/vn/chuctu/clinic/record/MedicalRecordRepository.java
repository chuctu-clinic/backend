package vn.chuctu.clinic.record;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicalRecordRepository extends JpaRepository<MedicalRecord, Long> {

    boolean existsByAppointmentId(Long appointmentId);

    Optional<MedicalRecord> findByAppointmentId(Long appointmentId);

    /** Khoá dòng để hai người sửa cùng lúc xếp hàng, mỗi lần sửa so với giá trị mới nhất. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MedicalRecord r where r.id = :id")
    Optional<MedicalRecord> findForUpdate(@Param("id") Long id);

    @Query("select r.appointmentId from MedicalRecord r where r.appointmentId in :ids")
    List<Long> appointmentIdsWithRecord(@Param("ids") Collection<Long> ids);

    List<MedicalRecord> findByPatientIdOrderByVisitDateDescCreatedAtDescIdDesc(Long patientId);

    /** Bệnh án thuộc các hồ sơ của tài khoản (BR.20), mới nhất trước. */
    @Query("select r from MedicalRecord r where r.patientId in (select p.id from Patient p where p.userId = :userId) "
            + "and (:patientId is null or r.patientId = :patientId) order by r.visitDate desc, r.createdAt desc, r.id desc")
    List<MedicalRecord> listOwned(@Param("userId") Long userId, @Param("patientId") Long patientId);

    @Query("select r from MedicalRecord r where r.appointmentId = :appointmentId "
            + "and r.patientId in (select p.id from Patient p where p.userId = :userId)")
    Optional<MedicalRecord> findOwnedByAppointment(@Param("appointmentId") Long appointmentId, @Param("userId") Long userId);
}
