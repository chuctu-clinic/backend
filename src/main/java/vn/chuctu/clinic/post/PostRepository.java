package vn.chuctu.clinic.post;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Các truy vấn danh sách. {@code pattern} là mẫu LIKE đã escape (Likes.contains) hoặc null; dấu escape ghi rõ là gạch chéo
 * ngược vì Hibernate mặc định gửi {@code escape ''}.
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    /** Bài công khai: category null nghĩa là mọi chuyên mục trừ TRANG. */
    String PUBLIC_FILTER = "p.status = 'PUBLISHED' and ((:category is null and p.category <> 'TRANG') or p.category = :category) "
            + "and (:pattern is null or p.title like :pattern escape '\\')";

    /** Bài nổi bật: bài ghim lên đầu (pinned IS NULL xếp sau), rồi bài đăng mới nhất. */
    @Query("select p from Post p where " + PUBLIC_FILTER
            + " order by case when p.pinned is null then 1 else 0 end, p.publishedAt desc, p.id desc")
    List<Post> featured(@Param("category") String category, @Param("pattern") String pattern, Pageable page);

    @Query("select p from Post p where " + PUBLIC_FILTER + " and (:excludeId is null or p.id <> :excludeId) "
            + "order by p.publishedAt desc, p.id desc")
    List<Post> listPublished(@Param("category") String category, @Param("pattern") String pattern,
            @Param("excludeId") Long excludeId, Pageable page);

    @Query("select count(p) from Post p where " + PUBLIC_FILTER)
    long countPublished(@Param("category") String category, @Param("pattern") String pattern);

    String ADMIN_FILTER = "p.category <> 'TRANG' and (:status is null or p.status = :status) "
            + "and (:category is null or p.category = :category) and (:pattern is null or p.title like :pattern escape '\\')";

    /** Xếp theo ngày tạo: bài mới vào đầu danh sách, sửa, ẩn hay ghim bài không làm các trang xáo trộn. */
    @Query("select p from Post p where " + ADMIN_FILTER + " order by p.createdAt desc, p.id desc")
    List<Post> adminList(@Param("status") String status, @Param("category") String category, @Param("pattern") String pattern,
            Pageable page);

    @Query("select count(p) from Post p where " + ADMIN_FILTER)
    long adminCount(@Param("status") String status, @Param("category") String category, @Param("pattern") String pattern);

    @Query("select p.status, count(p) from Post p where p.category <> 'TRANG' group by p.status")
    List<Object[]> statusCounts();

    java.util.Optional<Post> findBySlugAndStatus(String slug, String status);

    java.util.Optional<Post> findByIdAndStatus(Long id, String status);

    /** Bản nháp mới sửa gần nhất (tổng quan của Admin). */
    List<Post> findTop5ByStatusOrderByUpdatedAtDescIdDesc(String status);
}
