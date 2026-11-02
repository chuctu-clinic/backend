package vn.chuctu.clinic.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import vn.chuctu.clinic.common.Constants;

/** Tài khoản đăng nhập (bảng users): người dùng, tiếp tân và quản trị viên. */
@Entity
@DynamicUpdate
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "dob")
    private LocalDate dob;

    @Column(name = "gender")
    private String gender;

    @Column(name = "address")
    private String address;

    /** USER | RECEPTIONIST | ADMIN (xem Constants.ROLES). */
    @Column(name = "role", nullable = false)
    private String role = "USER";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Lần cuối đổi thông tin cá nhân; không dùng updatedAt vì mỗi lần đăng nhập nó cũng đổi. */
    @Column(name = "info_updated_at")
    private Instant infoUpdatedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Thông tin trả về cho giao diện (toPublic của bản Node): ngày sinh dạng nửa đêm UTC, không có mật khẩu. */
    public Map<String, Object> toPublic() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(id));
        out.put("phone", phone);
        out.put("email", email);
        out.put("fullName", fullName);
        out.put("dob", dob == null ? null : dob.atStartOfDay(ZoneOffset.UTC).toInstant());
        out.put("gender", gender);
        out.put("address", address);
        out.put("role", role);
        out.put("isActive", active);
        out.put("mustChangePassword", mustChangePassword);
        out.put("lastLoginAt", lastLoginAt);
        out.put("createdAt", createdAt);
        out.put("infoUpdatedAt", infoUpdatedAt != null ? infoUpdatedAt : createdAt);
        return out;
    }

    public boolean isStaff() {
        return Constants.STAFF_ROLES.contains(role);
    }

    public Long getId() { return id; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public LocalDate getDob() { return dob; }
    public void setDob(LocalDate dob) { this.dob = dob; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Instant getInfoUpdatedAt() { return infoUpdatedAt; }
    public void setInfoUpdatedAt(Instant infoUpdatedAt) { this.infoUpdatedAt = infoUpdatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
