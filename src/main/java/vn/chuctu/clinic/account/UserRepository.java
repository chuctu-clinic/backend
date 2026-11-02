package vn.chuctu.clinic.account;

import java.time.Instant;
import java.util.Optional;
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
}
