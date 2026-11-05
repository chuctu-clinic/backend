package vn.chuctu.clinic.patient;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    long countByUserId(Long userId);

    /** Hồ sơ của một tài khoản: theo quan hệ rồi ngày tạo (sort({ relationship: 1, createdAt: 1 }) của Node). */
    List<Patient> findByUserIdOrderByRelationshipAscCreatedAtAscIdAsc(Long userId);

    Optional<Patient> findByIdAndUserId(Long id, Long userId);

    Optional<Patient> findFirstByUserIdAndRelationshipOrderByIdAsc(Long userId, String relationship);

    boolean existsByUserIdAndRelationship(Long userId, String relationship);

    boolean existsByUserIdAndRelationshipAndIdNot(Long userId, String relationship, Long id);

    List<Patient> findTop10ByPhoneOrderByIdAsc(String phone);

    /** Hồ sơ cũ nhất dùng số này mà chưa gắn tài khoản nào. */
    Optional<Patient> findFirstByPhoneAndUserIdIsNullOrderByIdAsc(String phone);

    List<Patient> findTop10ByPhoneAndIdNotOrderByIdAsc(String phone, Long id);

    /**
     * Tìm theo họ tên, mã hoặc số điện thoại. {@code pattern} là mẫu LIKE đã escape (xem Likes.contains); so sánh theo
     * collation của cột (không phân biệt hoa thường, phân biệt dấu). Null nghĩa là không lọc.
     * Dấu escape ghi rõ là dấu gạch chéo ngược vì nếu bỏ, Hibernate gửi {@code escape ''} và MySQL hiểu là không có ký tự escape (% và _ mất tác dụng).
     */
    @Query("select p from Patient p where (:pattern is null or p.fullName like :pattern escape '\\' "
            + "or p.patientCode like :pattern escape '\\' or p.phone like :pattern escape '\\') "
            + "and (:locked is null or p.locked = :locked)")
    Page<Patient> search(@Param("pattern") String pattern, @Param("locked") Boolean locked, Pageable pageable);
}
