package vn.chuctu.clinic.queue;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Chỉ các truy vấn đọc; ghi nằm trong QueueService. */
public interface CheckinRepository extends JpaRepository<Checkin, Long> {

    List<Checkin> findByVisitDateAndStatus(LocalDate visitDate, String status);

    List<Checkin> findByVisitDateAndStatusIn(LocalDate visitDate, Collection<String> statuses);

    Optional<Checkin> findFirstByAppointmentIdAndStatus(Long appointmentId, String status);

    Optional<Checkin> findFirstByAppointmentIdAndStatusIn(Long appointmentId, Collection<String> statuses);

    List<Checkin> findByAppointmentIdInAndStatusIn(Collection<Long> appointmentIds, Collection<String> statuses);
}
