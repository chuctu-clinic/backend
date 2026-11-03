package vn.chuctu.clinic.account;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByPhone(String phone);

    Optional<User> findByPhone(String phone);

    Optional<User> findByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);

    /** Chỉ cập nhật họ tên, không ghi đè các cột khác mà thao tác khác có thể vừa đổi. */
    @Transactional
    @Modifying
    @Query("update User u set u.fullName = :fullName, u.updatedAt = :now where u.id = :id")
    int updateFullName(@Param("id") Long id, @Param("fullName") String fullName, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("update User u set u.email = :email, u.updatedAt = :now where u.id = :id")
    int updateEmail(@Param("id") Long id, @Param("email") String email, @Param("now") Instant now);

    /**
     * Đặt mật khẩu mới và bỏ yêu cầu đổi mật khẩu tạm, chỉ khi mật khẩu vẫn là {@code oldHash}: hai yêu cầu đặt lại cùng
     * lúc thì chỉ một cái thắng. Trả 0 nếu mật khẩu đã đổi.
     */
    @Transactional
    @Modifying
    @Query("update User u set u.passwordHash = :hash, u.mustChangePassword = false, u.updatedAt = :now "
            + "where u.id = :id and u.passwordHash = :oldHash")
    int resetPassword(@Param("id") Long id, @Param("hash") String hash, @Param("oldHash") String oldHash,
            @Param("now") Instant now);

    /** Ghi lần đăng nhập gần nhất, không đè các cột khác (admin có thể vừa khoá, đổi vai trò hay cấp mật khẩu tạm). */
    @Transactional
    @Modifying
    @Query("update User u set u.lastLoginAt = :now, u.updatedAt = :now where u.id = :id")
    int recordLogin(@Param("id") Long id, @Param("now") Instant now);

    boolean existsByPhoneAndIdNot(String phone, Long id);

    /**
     * Danh sách tài khoản cho Admin. {@code pattern} là mẫu LIKE đã escape (Likes.contains) hoặc null; dấu escape ghi rõ là gạch
     * chéo ngược vì Hibernate mặc định gửi {@code escape ''}. role và active null nghĩa là không lọc.
     */
    @Query("select u from User u where (:pattern is null or u.fullName like :pattern escape '\\' "
            + "or u.phone like :pattern escape '\\' or u.email like :pattern escape '\\') "
            + "and (:role is null or u.role = :role) and (:active is null or u.active = :active)")
    Page<User> search(@Param("pattern") String pattern, @Param("role") String role, @Param("active") Boolean active,
            Pageable pageable);

    @Query("select u.role, count(u) from User u group by u.role")
    List<Object[]> roleCounts();

    /** Khoá hoặc mở khoá: chỉ ghi cột is_active, không đè lần đăng nhập hay thông tin người dùng vừa sửa. */
    @Transactional
    @Modifying
    @Query("update User u set u.active = :active, u.updatedAt = :now where u.id = :id")
    int setActive(@Param("id") Long id, @Param("active") boolean active, @Param("now") Instant now);

    /** Admin cấp mật khẩu tạm: chỉ ghi mật khẩu và cờ phải đổi mật khẩu. */
    @Transactional
    @Modifying
    @Query("update User u set u.passwordHash = :hash, u.mustChangePassword = true, u.updatedAt = :now where u.id = :id")
    int setTempPassword(@Param("id") Long id, @Param("hash") String hash, @Param("now") Instant now);
}
