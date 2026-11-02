package vn.chuctu.clinic.appointment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    boolean existsByPatientIdAndSlotDateAndStatusIn(Long patientId, LocalDate slotDate, Collection<String> statuses);

    Optional<Appointment> findByIdAndUserId(Long id, Long userId);

    Optional<Appointment> findByIdAndUserIdAndStatus(Long id, Long userId, String status);

    Optional<Appointment> findByTicketCode(String ticketCode);

    List<Appointment> findTop200ByPatientIdOrderBySlotDateDescSlotStartDescIdDesc(Long patientId);

    /** Phiếu của tài khoản: do tài khoản đặt, hoặc thuộc một hồ sơ của tài khoản (BR.20). */
    @Query("select a from Appointment a where a.id = :id and (a.userId = :userId "
            + "or a.patientId in (select p.id from Patient p where p.userId = :userId))")
    Optional<Appointment> findOwned(@Param("id") Long id, @Param("userId") Long userId);

    @Query("select a from Appointment a where a.status in :statuses and (a.userId = :userId "
            + "or a.patientId in (select p.id from Patient p where p.userId = :userId)) "
            + "order by a.slotDate desc, a.slotStart desc, a.id desc limit 200")
    List<Appointment> listOwned(@Param("userId") Long userId, @Param("statuses") Collection<String> statuses);

    /**
     * Lịch một ngày. {@code pattern} là mẫu LIKE đã escape (Likes.contains) hoặc null; khớp mã phiếu, hoặc họ tên, số điện
     * thoại, mã bệnh nhân của hồ sơ. Dấu escape ghi rõ là gạch chéo ngược vì Hibernate mặc định gửi {@code escape ''}.
     */
    @Query("select a from Appointment a where a.slotDate = :date and (:status is null or a.status = :status) "
            + "and (:pattern is null or a.ticketCode like :pattern escape '\\' or a.patientId in (select p.id from Patient p "
            + "where p.fullName like :pattern escape '\\' or p.phone like :pattern escape '\\' "
            + "or p.patientCode like :pattern escape '\\')) order by a.slotStart asc, a.createdAt asc, a.id asc")
    List<Appointment> listDay(@Param("date") LocalDate date, @Param("status") String status, @Param("pattern") String pattern);

    @Query("select a.status, count(a) from Appointment a where a.slotDate = :date group by a.status")
    List<Object[]> countByStatusOnDate(@Param("date") LocalDate date);

    @Query("select distinct a.slotDate from Appointment a where a.slotDate >= :start and a.slotDate < :end "
            + "and a.status <> 'CANCELLED' order by a.slotDate")
    List<LocalDate> datesWithTickets(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
