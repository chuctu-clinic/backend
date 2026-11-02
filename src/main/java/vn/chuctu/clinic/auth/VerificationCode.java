package vn.chuctu.clinic.auth;

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

/** Một lần xin mã xác nhận (bảng verification_codes). Mã không lưu thẳng, chỉ lưu bản băm HMAC. */
@Entity
@Table(name = "verification_codes")
public class VerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone", nullable = false)
    private String phone;

    /** Địa chỉ nhận mã khi gửi qua email; null: gửi tin nhắn tới số điện thoại. */
    @Column(name = "email")
    private String email;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    /** REGISTER | NEW_DEVICE | RESET_PASSWORD | CHANGE_EMAIL. */
    @Column(name = "purpose", nullable = false)
    private String purpose;

    /** Thông tin đăng ký chờ xác nhận (REGISTER) hoặc email mới (CHANGE_EMAIL). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "register_data")
    private Map<String, Object> registerData;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public Map<String, Object> getRegisterData() { return registerData; }
    public void setRegisterData(Map<String, Object> registerData) { this.registerData = registerData; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
