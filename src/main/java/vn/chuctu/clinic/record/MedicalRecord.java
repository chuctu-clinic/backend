package vn.chuctu.clinic.record;

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

/**
 * Phiếu kết quả khám, gọi là bệnh án (bảng medical_records). Mỗi phiếu khám có tối đa một bệnh án (UNIQUE appointment_id).
 * DynamicUpdate: chỉ ghi các cột đã đổi. Lịch sử sửa nằm ở bảng medical_record_edits (xem {@link MedicalRecordEdit}).
 */
@Entity
@DynamicUpdate
@Table(name = "medical_records")
@EntityListeners(AuditingEntityListener.class)
public class MedicalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private Long appointmentId;

    @Column(name = "checkin_id", updatable = false)
    private Long checkinId;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(name = "diagnosis", columnDefinition = "text")
    private String diagnosis;

    @Column(name = "pulse")
    private String pulse;

    @Column(name = "blood_pressure")
    private String bloodPressure;

    @Column(name = "treatment_acupuncture", columnDefinition = "text")
    private String treatmentAcupuncture;

    @Column(name = "treatment_acupressure", columnDefinition = "text")
    private String treatmentAcupressure;

    @Column(name = "treatment_other", columnDefinition = "text")
    private String treatmentOther;

    @Column(name = "medical_history", columnDefinition = "text")
    private String medicalHistory;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "doctor_name")
    private String doctorName;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public void setPatientId(Long patientId) { this.patientId = patientId; }
    public Long getAppointmentId() { return appointmentId; }
    public void setAppointmentId(Long appointmentId) { this.appointmentId = appointmentId; }
    public Long getCheckinId() { return checkinId; }
    public void setCheckinId(Long checkinId) { this.checkinId = checkinId; }
    public LocalDate getVisitDate() { return visitDate; }
    public void setVisitDate(LocalDate visitDate) { this.visitDate = visitDate; }
    public String getDiagnosis() { return diagnosis; }
    public void setDiagnosis(String diagnosis) { this.diagnosis = diagnosis; }
    public String getPulse() { return pulse; }
    public void setPulse(String pulse) { this.pulse = pulse; }
    public String getBloodPressure() { return bloodPressure; }
    public void setBloodPressure(String bloodPressure) { this.bloodPressure = bloodPressure; }
    public String getTreatmentAcupuncture() { return treatmentAcupuncture; }
    public void setTreatmentAcupuncture(String treatmentAcupuncture) { this.treatmentAcupuncture = treatmentAcupuncture; }
    public String getTreatmentAcupressure() { return treatmentAcupressure; }
    public void setTreatmentAcupressure(String treatmentAcupressure) { this.treatmentAcupressure = treatmentAcupressure; }
    public String getTreatmentOther() { return treatmentOther; }
    public void setTreatmentOther(String treatmentOther) { this.treatmentOther = treatmentOther; }
    public String getMedicalHistory() { return medicalHistory; }
    public void setMedicalHistory(String medicalHistory) { this.medicalHistory = medicalHistory; }
    public LocalDate getFollowUpDate() { return followUpDate; }
    public void setFollowUpDate(LocalDate followUpDate) { this.followUpDate = followUpDate; }
    public String getDoctorName() { return doctorName; }
    public void setDoctorName(String doctorName) { this.doctorName = doctorName; }
    public Long getRecordedBy() { return recordedBy; }
    public void setRecordedBy(Long recordedBy) { this.recordedBy = recordedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
