package vn.chuctu.clinic.post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Bài viết (bảng posts). Các trang Giới thiệu, Dịch vụ, Hướng dẫn, Liên hệ là bài viết có slug cố định và chuyên mục TRANG.
 * DynamicUpdate: chỉ ghi các cột đã đổi, để sửa bài không đè việc ghim vừa xảy ra ở nơi khác.
 */
@Entity
@DynamicUpdate
@Table(name = "posts")
@EntityListeners(AuditingEntityListener.class)
public class Post {

    /** Chuyên mục bài viết (POST_CATEGORIES của Node), giữ thứ tự khai báo. */
    public static final Map<String, String> CATEGORY_LABELS = labels();
    public static final List<String> PAGE_SLUGS = List.of("gioi-thieu", "dich-vu", "huong-dan", "lien-he");

    private static Map<String, String> labels() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("TIN_TUC", "Tin tức");
        map.put("HUONG_DAN", "Hướng dẫn");
        map.put("THIEN_NGUYEN", "Thiện nguyện");
        map.put("SU_KIEN", "Sự kiện");
        map.put("TRANG", "Trang thông tin");
        return Collections.unmodifiableMap(map);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "slug")
    private String slug;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "summary", nullable = false)
    private String summary = "";

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "author_name", nullable = false)
    private String authorName;

    @Column(name = "content", nullable = false)
    private String content = "";

    /** DRAFT | PUBLISHED | HIDDEN (HIDDEN: đã công khai rồi tạm ẩn, giữ nguyên ngày đăng). */
    @Column(name = "status", nullable = false)
    private String status = "DRAFT";

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Chỉ nhận 1 hoặc null (cột TINYINT, UNIQUE bỏ qua NULL nên mỗi lúc chỉ một bài ghim). Ghim đi qua SQL trực tiếp. */
    @Column(name = "pinned")
    private Byte pinned;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isPage() {
        return slug != null && PAGE_SLUGS.contains(slug);
    }

    public boolean isPinned() {
        return pinned != null;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSlug() { return slug; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public void clearPinned() { this.pinned = null; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
