package vn.chuctu.clinic.record;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Một lần sửa bệnh án (bảng medical_record_edits), thay cho mảng editHistory của Node. Đọc theo thứ tự id là thứ tự đã lưu. */
@Entity
@Table(name = "medical_record_edits")
public class MedicalRecordEdit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "medical_record_id", nullable = false, updatable = false)
    private Long medicalRecordId;

    @Column(name = "edited_by", updatable = false)
    private Long editedBy;

    @Column(name = "edited_by_name", updatable = false)
    private String editedByName;

    @Column(name = "edited_at", updatable = false)
    private Instant editedAt;

    /** Dạng {tênTrường: {from, to}}, ví dụ diagnosis: {from: "Đau lưng", to: "Thoát vị"}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", updatable = false)
    private Map<String, Object> changes;

    public Long getId() { return id; }
    public Long getMedicalRecordId() { return medicalRecordId; }
    public void setMedicalRecordId(Long medicalRecordId) { this.medicalRecordId = medicalRecordId; }
    public Long getEditedBy() { return editedBy; }
    public void setEditedBy(Long editedBy) { this.editedBy = editedBy; }
    public String getEditedByName() { return editedByName; }
    public void setEditedByName(String editedByName) { this.editedByName = editedByName; }
    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }
    public Map<String, Object> getChanges() { return changes; }
    public void setChanges(Map<String, Object> changes) { this.changes = changes; }
}
