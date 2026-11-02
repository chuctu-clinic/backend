package vn.chuctu.clinic.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Lượt check-in (bảng checkins), chỉ để đọc. Mọi thay đổi đi qua câu INSERT hoặc UPDATE có điều kiện trong QueueService,
 * không qua save, để chuyển trạng thái và giữ ghế nguyên tử. Hai cột sinh tự động active_appointment_id và
 * in_room_seat_no (ràng buộc duy nhất có điều kiện) không ánh xạ.
 */
@Entity
@Table(name = "checkins")
public class Checkin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private Long appointmentId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "visit_date", nullable = false, updatable = false)
    private LocalDate visitDate;

    @Column(name = "check_in_time", nullable = false, updatable = false)
    private Instant checkInTime;

    /** PRIORITY hoặc NORMAL. */
    @Column(name = "priority_group", nullable = false)
    private String priorityGroup;

    /** WAITING, IN_ROOM, COMPLETED hoặc REVERTED. */
    @Column(name = "status", nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "seat_no")
    private Integer seatNo;

    @Column(name = "entered_at")
    private Instant enteredAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public Long getAppointmentId() { return appointmentId; }
    public Long getPatientId() { return patientId; }
    public LocalDate getVisitDate() { return visitDate; }
    public Instant getCheckInTime() { return checkInTime; }
    public String getPriorityGroup() { return priorityGroup; }
    public String getStatus() { return status; }
    public Integer getSeatNo() { return seatNo; }
    public Instant getEnteredAt() { return enteredAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
