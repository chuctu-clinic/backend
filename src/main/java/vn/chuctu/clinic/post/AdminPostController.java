package vn.chuctu.clinic.post;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Paging;
import vn.chuctu.clinic.security.CurrentUser;

/** Quản lý bài viết và tải ảnh (UC.5.1 – UC.5.5), phần bài viết của routes/admin.routes.js. Quyền Admin do SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
public class AdminPostController {

    private final PostService posts;
    private final UploadService uploads;
    private final CurrentUser currentUser;

    public AdminPostController(PostService posts, UploadService uploads, CurrentUser currentUser) {
        this.posts = posts;
        this.uploads = uploads;
        this.currentUser = currentUser;
    }

    /**
     * Ảnh cho bài viết: gửi thẳng nội dung ảnh (đã thu nhỏ ở trình duyệt), tối đa 3 MB như express.raw. Controller tự đọc body
     * có giới hạn, không qua bộ chuyển đổi nào của Spring, và chỉ đọc khi loại nội dung là ảnh.
     */
    @PostMapping("/uploads")
    ResponseEntity<Map<String, Object>> upload(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        byte[] data = null;
        if (UploadService.readsBody(type)) {
            // Không tin Content-Length (có thể vắng hoặc sai): readLimited tự chặn ở 3 MB. Cũng không từ chối trước theo
            // Content-Length: đóng kết nối khi chưa đọc body làm máy khách mất phản hồi 500 (Tomcat chỉ nuốt tối đa 2 MB thừa)
            data = UploadService.readLimited(request.getInputStream());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(uploads.saveImage(currentUser.require(), type, data));
    }

    @GetMapping("/posts")
    Map<String, Object> list(@RequestParam(required = false) String status, @RequestParam(required = false) String category,
            @RequestParam(required = false) String q, @RequestParam(required = false) String page,
            @RequestParam(required = false) String limit) {
        Paging paging = Paging.of(page, limit, 20);
        Map<String, Object> result = new LinkedHashMap<>(posts.adminList(status, category, q, paging));
        result.put("page", paging.page());
        result.put("limit", paging.limit());
        return result;
    }

    @PostMapping("/posts")
    ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) JsonNode json) {
        var data = PostForms.create(json);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("post", posts.createPost(currentUser.require(), data)));
    }

    @GetMapping("/posts/{id}")
    Map<String, Object> get(@PathVariable String id) {
        return Map.of("post", posts.adminGet(postId(id)));
    }

    @PutMapping("/posts/{id}")
    Map<String, Object> update(@PathVariable String id, @RequestBody(required = false) JsonNode json) {
        // Như Node: kiểm tra dữ liệu trước, tra cứu mã sau
        var data = PostForms.update(json);
        return Map.of("post", posts.updatePost(postId(id), data));
    }

    @PostMapping("/posts/{id}/pin")
    Map<String, Object> pin(@PathVariable String id) {
        return Map.of("post", posts.setPinned(postId(id), true));
    }

    @PostMapping("/posts/{id}/unpin")
    Map<String, Object> unpin(@PathVariable String id) {
        return Map.of("post", posts.setPinned(postId(id), false));
    }

    @DeleteMapping("/posts/{id}")
    Map<String, Object> delete(@PathVariable String id) {
        return posts.deletePost(postId(id));
    }

    /** Mã bài là số; mã không phải số hay quá lớn là CastError của Mongoose: 404 chung "Không tìm thấy dữ liệu.". */
    private static long postId(String id) {
        Long parsed = Ids.parse(id);
        if (parsed == null) throw Errors.notFound();
        return parsed;
    }
}
