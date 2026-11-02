package vn.chuctu.clinic.appointment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Phiếu khám (bảng appointments). Đổi trạng thái đi qua UPDATE có điều kiện trong AppointmentService, không qua save. */
@Entity
@DynamicUpdate
@Table(name = "appointments")
@EntityListeners(AuditingEntityListener.class)
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Dạng CT-PK-26-2809-004. */
    @Column(name = "ticket_code", nullable = false)
    private String ticketCode;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "service_type", nullable = false)
    private String serviceType;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Column(name = "slot_start", nullable = false)
    private String slotStart;

    @Column(name = "expected_time", nullable = false)
    private Instant expectedTime;

    @Column(name = "status", nullable = false)
    private String status;

    /** ONLINE hoặc COUNTER. */
    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "rescheduled_from_id")
    private Long rescheduledFromId;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public String getTicketCode() { return ticketCode; }
    public void setTicketCode(String ticketCode) { this.ticketCode = ticketCode; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getPatientId() { return patientId; }
    public void setPatientId(Long patientId) { this.patientId = patientId; }
    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }
    public LocalDate getSlotDate() { return slotDate; }
    public void setSlotDate(LocalDate slotDate) { this.slotDate = slotDate; }
    public String getSlotStart() { return slotStart; }
    public void setSlotStart(String slotStart) { this.slotStart = slotStart; }
    public Instant getExpectedTime() { return expectedTime; }
    public void setExpectedTime(Instant expectedTime) { this.expectedTime = expectedTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getRescheduledFromId() { return rescheduledFromId; }
    public void setRescheduledFromId(Long rescheduledFromId) { this.rescheduledFromId = rescheduledFromId; }
    public String getCancelReason() { return cancelReason; }
    public Long getCancelledBy() { return cancelledBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
