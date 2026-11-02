package vn.chuctu.clinic.auth;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {

    /** Ghi lần dùng mới nhất của thiết bị đã tin cậy; trả số dòng khớp (0: thiết bị chưa có trong danh sách). */
    @Transactional
    @Modifying
    @Query("update TrustedDevice d set d.lastUsedAt = :now where d.userId = :userId and d.deviceHash = :hash")
    int touch(@Param("userId") Long userId, @Param("hash") String hash, @Param("now") Instant now);

    /** Thêm thiết bị nếu chưa có, có rồi thì chỉ cập nhật lần dùng (không lỗi khi hai request cùng lúc). */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO trusted_devices (user_id, device_hash, created_at, last_used_at) "
            + "VALUES (:userId, :hash, :now, :now) ON DUPLICATE KEY UPDATE last_used_at = :now", nativeQuery = true)
    int upsert(@Param("userId") Long userId, @Param("hash") String hash, @Param("now") Instant now);
}
