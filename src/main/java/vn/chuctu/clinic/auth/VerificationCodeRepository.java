package vn.chuctu.clinic.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {

    /** Các lần xin mã tin nhắn của số điện thoại từ thời điểm {@code since}, mới nhất trước. */
    List<VerificationCode> findByPhoneAndEmailIsNullAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
            String phone, Instant since);

    /** Mã còn dùng được (chưa dùng, chưa hết hạn) mới nhất của số điện thoại và mục đích. */
    Optional<VerificationCode> findFirstByPhoneAndPurposeAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDescIdDesc(
            String phone, String purpose, Instant now);

    Optional<VerificationCode> findFirstByPhoneAndPurposeOrderByCreatedAtDescIdDesc(String phone, String purpose);

    /** Mã cũ chưa dùng của cùng mục đích hết hiệu lực khi có mã mới. */
    @Transactional
    @Modifying
    @Query("update VerificationCode c set c.expiresAt = :now "
            + "where c.phone = :phone and c.purpose = :purpose and c.usedAt is null and c.expiresAt > :now")
    int expireOpenCodes(@Param("phone") String phone, @Param("purpose") String purpose, @Param("now") Instant now);

    /**
     * Cộng một lần nhập sai. Luôn chạy trong giao dịch riêng, commit ngay: request sau đó trả lỗi (hay đang nằm trong
     * một giao dịch bị rollback) cũng không làm mất lần nhập sai.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update VerificationCode c set c.attempts = c.attempts + 1 where c.id = :id")
    int addFailedAttempt(@Param("id") Long id);

    /** Đánh dấu đã dùng; trả 0 nếu có request khác dùng trước. */
    @Transactional
    @Modifying
    @Query("update VerificationCode c set c.usedAt = :now where c.id = :id and c.usedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);
}
