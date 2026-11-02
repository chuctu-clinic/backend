package vn.chuctu.clinic.post;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.chuctu.clinic.appointment.AppointmentService;
import vn.chuctu.clinic.auth.SmsGateway;
import vn.chuctu.clinic.common.Constants;
import vn.chuctu.clinic.common.Paging;
import vn.chuctu.clinic.common.Rules;
import vn.chuctu.clinic.common.VnTime;

/** API công khai không cần đăng nhập (routes/public.routes.js): danh mục dùng chung, ảnh bài viết và bài viết (UC.4.1 – UC.4.3). */
@RestController
@RequestMapping("/api")
public class PublicController {

    private final PostService posts;
    private final UploadService uploads;
    private final SmsGateway sms;
    private final Clock clock;

    public PublicController(PostService posts, UploadService uploads, SmsGateway sms, Clock clock) {
        this.posts = posts;
        this.uploads = uploads;
        this.sms = sms;
        this.clock = clock;
    }

    private static List<Map<String, String>> valueLabel(Map<String, String> labels) {
        return labels.entrySet().stream().map(e -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("value", e.getKey());
            item.put("label", e.getValue());
            return item;
        }).toList();
    }

    /** Danh mục dùng chung cho giao diện: nội dung khám, khung giờ, trạng thái... */
    @GetMapping("/meta")
    Map<String, Object> meta() {
        Map<String, Object> otp = new LinkedHashMap<>();
        // Cách gửi mã xác nhận: "firebase" thì giao diện tự gửi qua Firebase với cấu hình này
        otp.put("provider", sms.usesFirebase() ? "firebase" : "server");
        otp.put("firebase", sms.firebaseWebConfig());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", VnTime.today(clock.instant()).toString());
        out.put("otp", otp);
        out.put("serviceTypes", valueLabel(Constants.SERVICE_TYPES));
        out.put("slots", Rules.SLOTS);
        out.put("slotCapacity", Rules.SLOT_CAPACITY);
        out.put("userMaxDays", Rules.BOOKING_USER_MAX_DAYS);
        out.put("counterMaxDays", Rules.BOOKING_COUNTER_MAX_DAYS);
        out.put("seats", Rules.QUEUE_SEATS);
        out.put("priorityEarlyMinutes", Rules.QUEUE_PRIORITY_EARLY_MINUTES);
        out.put("slotMinutes", Rules.SLOT_MINUTES);
        out.put("maxProfiles", Rules.MAX_PROFILES_PER_ACCOUNT);
        out.put("relationships", List.copyOf(Constants.RELATIONSHIPS.keySet()));
        out.put("statusLabels", Constants.STATUS_LABELS);
        out.put("cancelReasons", AppointmentService.COUNTER_CANCEL_REASONS);
        out.put("postCategories", valueLabel(Post.CATEGORY_LABELS));
        return out;
    }

    /** Ảnh bài viết do Admin tải lên; mã ảnh không đổi nên trình duyệt giữ lâu được. */
    @GetMapping("/uploads/{id}")
    ResponseEntity<byte[]> image(@PathVariable String id) {
        Upload image = uploads.getImage(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.getContentType()))
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .body(image.getData());
    }

    @GetMapping("/posts")
    Map<String, Object> list(@RequestParam(required = false) String category, @RequestParam(required = false) String q,
            @RequestParam(required = false) String page, @RequestParam(required = false) String limit,
            @RequestParam(required = false) String featured) {
        Paging paging = Paging.of(page, limit, 9);
        Map<String, Object> result = new LinkedHashMap<>(posts.listPublished(category, q, paging, "1".equals(featured)));
        result.put("page", paging.page());
        result.put("limit", paging.limit());
        return result;
    }

    @GetMapping("/posts/{idOrSlug}")
    Map<String, Object> get(@PathVariable String idOrSlug) {
        return Map.of("post", posts.getPublished(idOrSlug));
    }
}
