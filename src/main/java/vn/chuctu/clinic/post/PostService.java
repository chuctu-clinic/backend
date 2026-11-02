package vn.chuctu.clinic.post;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Likes;
import vn.chuctu.clinic.common.Paging;
import vn.chuctu.clinic.common.Tx;
import vn.chuctu.clinic.post.PostForms.PostData;

/** Bài viết (services/post.service.js): phần công khai (BR.08) và phần quản trị (BR.09, BR.10). */
@Service
public class PostService {

    private static final List<String> STATUSES = List.of("DRAFT", "PUBLISHED", "HIDDEN");
    private static final String NOT_FOUND = "Không tìm thấy bài viết.";

    private final PostRepository posts;
    private final JdbcTemplate jdbc;
    private final Tx tx;
    private final Clock clock;

    public PostService(PostRepository posts, JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.posts = posts;
        this.jdbc = jdbc;
        this.tx = new Tx(transactionManager);
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    public Map<String, Object> postView(Post p, boolean full) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(p.getId()));
        out.put("title", p.getTitle());
        out.put("slug", p.getSlug());
        out.put("category", p.getCategory());
        out.put("categoryLabel", Post.CATEGORY_LABELS.get(p.getCategory()));
        out.put("summary", p.getSummary());
        out.put("thumbnailUrl", p.getThumbnailUrl());
        out.put("authorName", p.getAuthorName());
        out.put("status", p.getStatus());
        out.put("pinned", p.isPinned());
        out.put("publishedAt", p.getPublishedAt());
        out.put("createdAt", p.getCreatedAt());
        out.put("updatedAt", p.getUpdatedAt());
        if (full) out.put("content", p.getContent());
        return out;
    }

    /** Mẫu LIKE từ ô tìm kiếm (q.trim() rồi escapeRegex của Node), null nếu không tìm. */
    private static String pattern(String q) {
        return q == null || q.isEmpty() ? null : Likes.contains(Body.jsTrim(q));
    }

    /** Phân trang MySQL: nếu vị trí bắt đầu vượt int thì chắc chắn không còn bài nào. */
    private static boolean beyondEnd(Paging paging) {
        return paging.offset() > Integer.MAX_VALUE;
    }

    private static PageRequest page(Paging paging, int size) {
        return PageRequest.of(paging.page() - 1, size);
    }

    // ---------- Công khai: chỉ bài đã đăng (BR.08) ----------

    public Map<String, Object> listPublished(String category, String q, Paging paging, boolean withFeatured) {
        // POST_CATEGORIES[category] của Node: chuyên mục hợp lệ (kể cả TRANG) thì lọc, không thì lấy mọi chuyên mục trừ TRANG
        String cat = category != null && Post.CATEGORY_LABELS.containsKey(category) ? category : null;
        String pattern = pattern(q);
        // Bài nổi bật đứng yên ở mọi trang: bài ghim, chưa ghim thì bài mới nhất; phân trang chỉ chia các bài còn lại
        Post featured = withFeatured ? posts.featured(cat, pattern, PageRequest.of(0, 1)).stream().findFirst().orElse(null) : null;
        List<Post> items = beyondEnd(paging) ? List.of()
                : posts.listPublished(cat, pattern, featured == null ? null : featured.getId(), page(paging, paging.limit()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("featured", featured == null ? null : postView(featured, false));
        out.put("items", items.stream().map(p -> postView(p, false)).toList());
        out.put("total", posts.countPublished(cat, pattern));
        return out;
    }

    /** Toàn chữ số (vừa một long) là ID, còn lại là slug. */
    public Map<String, Object> getPublished(String idOrSlug) {
        Long id = Ids.parse(idOrSlug);
        Post post = (id != null ? posts.findByIdAndStatus(id, "PUBLISHED") : posts.findBySlugAndStatus(idOrSlug, "PUBLISHED"))
                .orElseThrow(() -> Errors.notFound("Bài viết không tồn tại hoặc chưa được đăng."));
        return postView(post, true);
    }

    // ---------- Quản trị (BR.09, BR.10) ----------

    private static void assertPublishable(String title, String summary, String category, String authorName, String content) {
        Map<String, Object> missing = new LinkedHashMap<>();
        if (title == null || title.isEmpty()) missing.put("title", "Bài viết cần có tiêu đề.");
        if (summary == null || Body.jsTrim(summary).isEmpty()) missing.put("summary", "Bài viết cần có mô tả.");
        if (category == null) missing.put("category", "Bài viết cần có chuyên mục.");
        if (authorName == null || authorName.isEmpty()) missing.put("authorName", "Bài viết cần có tác giả.");
        if (content == null || Body.jsTrim(content).isEmpty()) missing.put("content", "Bài viết cần có nội dung.");
        if (!missing.isEmpty()) {
            throw Errors.badRequest("Bài viết còn thiếu thông tin nên chưa đăng được.", Map.of("fields", missing), "NOT_PUBLISHABLE");
        }
    }

    /** Trang thông tin cố định không hiện trong danh sách quản trị: website chỉ có 4 danh mục bài viết. */
    public Map<String, Object> adminList(String status, String category, String q, Paging paging) {
        String st = status != null && STATUSES.contains(status) ? status : null;
        String cat = category != null && !category.equals("TRANG") && Post.CATEGORY_LABELS.containsKey(category) ? category : null;
        String pattern = pattern(q);
        List<Post> items = beyondEnd(paging) ? List.of() : posts.adminList(st, cat, pattern, page(paging, paging.limit()));
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of("PUBLISHED", "DRAFT", "HIDDEN")) counts.put(s, 0L);
        for (Object[] row : posts.statusCounts()) counts.put((String) row[0], ((Number) row[1]).longValue());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items.stream().map(p -> postView(p, false)).toList());
        out.put("total", posts.adminCount(st, cat, pattern));
        out.put("statusCounts", counts);
        return out;
    }

    public Map<String, Object> adminGet(long id) {
        return postView(posts.findById(id).orElseThrow(() -> Errors.notFound(NOT_FOUND)), true);
    }

    public Map<String, Object> createPost(User admin, PostData data) {
        Post post = new Post();
        post.setTitle(data.title().get());
        post.setCategory(data.category().get());
        post.setSummary(data.summary().get());
        post.setThumbnailUrl(data.thumbnailUrl().get());
        post.setContent(data.content().get());
        post.setAuthorId(admin.getId());
        String author = data.authorName().get();
        post.setAuthorName(author == null || author.isEmpty() ? admin.getFullName() : author);
        post.setStatus(data.status().get());
        // Bản nháp cũng giữ ngày đăng đã chọn, để khi công khai dùng đúng ngày đó
        post.setPublishedAt(data.publishedAt().get());
        if ("PUBLISHED".equals(post.getStatus())) {
            assertPublishable(post.getTitle(), post.getSummary(), post.getCategory(), post.getAuthorName(), post.getContent());
            if (post.getPublishedAt() == null) post.setPublishedAt(now());
        }
        return postView(posts.saveAndFlush(post), true);
    }

    @Transactional
    public Map<String, Object> updatePost(long id, PostData data) {
        Post post = posts.findById(id).orElseThrow(() -> Errors.notFound(NOT_FOUND));
        if (data.title().isPresent()) post.setTitle(data.title().get());
        if (data.category().isPresent()) post.setCategory(data.category().get());
        if (data.summary().isPresent()) post.setSummary(data.summary().get());
        if (data.thumbnailUrl().isPresent()) post.setThumbnailUrl(data.thumbnailUrl().get());
        if (data.content().isPresent()) post.setContent(data.content().get());
        if (data.authorName().isPresent()) post.setAuthorName(data.authorName().get());
        if (post.isPage()) post.setCategory("TRANG");
        if (data.status().isPresent()) post.setStatus(data.status().get());
        if (data.publishedAt().get() != null) post.setPublishedAt(data.publishedAt().get());
        if ("PUBLISHED".equals(post.getStatus())) {
            assertPublishable(post.getTitle(), post.getSummary(), post.getCategory(), post.getAuthorName(), post.getContent());
            if (post.getPublishedAt() == null) post.setPublishedAt(now());
        } else {
            // Bài rời website thì thôi ghim, để lúc công khai lại không tự chiếm chỗ bài đang ghim
            post.clearPinned();
        }
        // Mongoose đòi title và authorName không rỗng khi lưu, lỗi đó rơi vào errorHandler thành 500
        if (post.getTitle().isEmpty() || post.getAuthorName().isEmpty()) throw Errors.serverError();
        posts.flush();
        return postView(post, true);
    }

    /**
     * Ghim không đổi giờ cập nhật (ghim không phải là sửa bài): SQL trực tiếp, bỏ ghim bài cũ rồi ghim bài này trong một giao dịch.
     * Hai lần ghim cùng lúc xếp hàng nhờ khoá hàng đang ghim; lần đầu tiên chưa có bài ghim nào thì một bên có thể gặp deadlock và
     * được làm lại.
     */
    public Map<String, Object> setPinned(long id, boolean pinned) {
        Post post = posts.findById(id).orElseThrow(() -> Errors.notFound(NOT_FOUND));
        if (pinned && (!"PUBLISHED".equals(post.getStatus()) || "TRANG".equals(post.getCategory()))) {
            throw Errors.badRequest("Chỉ ghim được bài viết đã công khai.", null, "NOT_PINNABLE");
        }
        tx.run(() -> {
            if (pinned) {
                jdbc.update("UPDATE posts SET pinned = NULL WHERE pinned = 1");
                jdbc.update("UPDATE posts SET pinned = 1 WHERE id = ?", id);
            } else {
                jdbc.update("UPDATE posts SET pinned = NULL WHERE id = ?", id);
            }
            return null;
        });
        return postView(posts.findById(id).orElseThrow(() -> Errors.notFound(NOT_FOUND)), true);
    }

    public Map<String, Object> deletePost(long id) {
        Post post = posts.findById(id).orElseThrow(() -> Errors.notFound(NOT_FOUND));
        if (post.isPage()) {
            throw Errors.badRequest("Đây là trang thông tin cố định của website, chỉ sửa được, không xoá được.");
        }
        posts.deleteById(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", true);
        return out;
    }
}
