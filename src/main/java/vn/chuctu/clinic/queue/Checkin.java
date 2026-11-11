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
 * không qua save, để chuyển trạng thái và giữ chỗ nguyên tử. Hai cột sinh tự động active_appointment_id và
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

    /** WAITING, EXAMINING, WAITING_TREATMENT, IN_ROOM (ở chỗ điều trị), WAITING_CONCLUSION, COMPLETED hoặc REVERTED. */
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

    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(name = "exam_started_at")
    private Instant examStartedAt;

    @Column(name = "symptoms")
    private String symptoms;

    @Column(name = "prelim_diagnosis")
    private String prelimDiagnosis;

    @Column(name = "medical_history")
    private String medicalHistory;

    @Column(name = "prelim_pulse")
    private String prelimPulse;

    @Column(name = "prelim_blood_pressure")
    private String prelimBloodPressure;

    /** Mã kỹ thuật cách nhau bởi dấu phẩy (Constants.TREATMENT_KINDS). */
    @Column(name = "treatment_kinds")
    private String treatmentKinds;

    @Column(name = "treatment_order")
    private String treatmentOrder;

    @Column(name = "treatment_other")
    private String treatmentOther;

    @Column(name = "ordered_at")
    private Instant orderedAt;

    @Column(name = "technician_id")
    private Long technicianId;

    @Column(name = "report_acupuncture")
    private String reportAcupuncture;

    @Column(name = "report_acupressure")
    private String reportAcupressure;

    @Column(name = "report_physio")
    private String reportPhysio;

    @Column(name = "report_other")
    private String reportOther;

    @Column(name = "report_note")
    private String reportNote;

    @Column(name = "treated_at")
    private Instant treatedAt;

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
    public Long getDoctorId() { return doctorId; }
    public Instant getExamStartedAt() { return examStartedAt; }
    public String getSymptoms() { return symptoms; }
    public String getPrelimDiagnosis() { return prelimDiagnosis; }
    public String getPrelimPulse() { return prelimPulse; }
    public String getPrelimBloodPressure() { return prelimBloodPressure; }
    public String getMedicalHistory() { return medicalHistory; }
    public String getTreatmentKinds() { return treatmentKinds; }
    public String getTreatmentOrder() { return treatmentOrder; }
    public String getTreatmentOther() { return treatmentOther; }
    public Instant getOrderedAt() { return orderedAt; }
    public Long getTechnicianId() { return technicianId; }
    public String getReportAcupuncture() { return reportAcupuncture; }
    public String getReportAcupressure() { return reportAcupressure; }
    public String getReportPhysio() { return reportPhysio; }
    public String getReportOther() { return reportOther; }
    public String getReportNote() { return reportNote; }
    public Instant getTreatedAt() { return treatedAt; }
}
