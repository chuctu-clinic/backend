package vn.chuctu.clinic.post;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Opt;

/**
 * Kiểm tra dữ liệu gửi lên của bài viết, thay cho postSchema trong routes/admin.routes.js. Tạo bài là postSchema, sửa bài là
 * postSchema.partial(): ô không gửi thì giữ nguyên, không áp giá trị mặc định.
 */
final class PostForms {

    static final String BAD_THUMBNAIL = "Đường dẫn ảnh chưa đúng.";
    private static final String CATEGORY_MESSAGE = "Vui lòng chọn chuyên mục.";
    private static final List<String> STATUSES = List.of("DRAFT", "PUBLISHED", "HIDDEN");
    private static final List<String> CATEGORIES = List.copyOf(Post.CATEGORY_LABELS.keySet());
    private static final Pattern UPLOAD_URL = Pattern.compile("^/api/uploads/\\d+$");
    /** z.string().datetime({ offset: true }) của zod 3: ngày giờ ISO, giây bắt buộc, phần lẻ tuỳ ý, múi giờ Z hoặc ±hh[:mm]. */
    private static final Pattern DATETIME = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T([01]\\d|2[0-3]):([0-5]\\d):([0-5]\\d)(?:\\.(\\d+))?(?:Z|([+-])(\\d{2})(?::?(\\d{2}))?)$");
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):(.*)$", Pattern.DOTALL);
    private static final List<String> SPECIAL_SCHEMES = List.of("http", "https", "ws", "wss", "ftp");

    /** Dữ liệu đã kiểm tra. Tạo bài: mọi ô đều present (đã áp mặc định) trừ authorName. */
    record PostData(Opt<String> title, Opt<String> category, Opt<String> summary, Opt<String> thumbnailUrl, Opt<String> authorName,
            Opt<Instant> publishedAt, Opt<String> content, Opt<String> status) {}

    private PostForms() {}

    static PostData create(JsonNode json) {
        PostData data = read(Body.of(json), true);
        if (data.title().get().isEmpty()) {
            // z.object({ title: z.string().min(1, ...) }) chạy lại sau khi postSchema đã qua
            throw Errors.validation(Map.of("title", "Bài viết cần có tiêu đề."));
        }
        return data;
    }

    static PostData update(JsonNode json) {
        return read(Body.of(json), false);
    }

    private static PostData read(Body b, boolean create) {
        Opt<String> title = b.optionalString("title", true, 200, "Tiêu đề tối đa 200 ký tự.");
        Opt<String> category = enumField(b, "category", CATEGORIES, CATEGORY_MESSAGE, create, null);
        Opt<String> summary = b.optionalString("summary", true, 300, "Mô tả tối đa 300 ký tự.");
        Opt<String> thumbnailUrl = thumbnailUrl(b);
        Opt<String> authorName = b.optionalString("authorName", true, 100, "String must contain at most 100 character(s)");
        boolean[] unusableOffset = new boolean[1];
        Opt<Instant> publishedAt = publishedAt(b, unusableOffset);
        Opt<String> content = b.optionalString("content", false, 100_000, "String must contain at most 100000 character(s)");
        Opt<String> status = enumField(b, "status", STATUSES, null, false, create ? "DRAFT" : null);
        b.check();
        // JavaScript đọc ra Invalid Date, Mongoose không lưu được: 500
        if (unusableOffset[0]) throw Errors.serverError();
        if (create) {
            if (title.isAbsent()) title = Opt.of("");
            if (summary.isAbsent()) summary = Opt.of("");
            if (content.isAbsent()) content = Opt.of("");
            if (thumbnailUrl.isAbsent()) thumbnailUrl = Opt.of(null);
        }
        return new PostData(title, category, summary, thumbnailUrl, authorName, publishedAt, content, status);
    }

    /**
     * z.enum([...]): ô bắt buộc khi {@code required} (thiếu thì báo lỗi), ngược lại không gửi là absent (hoặc {@code fallback}
     * khi là default). Gửi sai thì báo lỗi như zod.
     */
    private static Opt<String> enumField(Body b, String field, List<String> allowed, String message, boolean required, String fallback) {
        if (b.node(field) == null && !required) return fallback == null ? Opt.absent() : Opt.of(fallback);
        String value = b.requiredEnum(field, allowed, message);
        return value == null ? Opt.absent() : Opt.of(value);
    }

    /**
     * z.union([url đã trim, /api/uploads/số, "", null]).optional().transform(v => v || null). Union báo lỗi của nhánh đầu có
     * kiểu đúng nhưng kiểm tra sai ({@link #BAD_THUMBNAIL}); không phải chuỗi thì "Invalid input". Nhánh /api/uploads/ khớp trên
     * chuỗi gốc, không trim.
     */
    private static Opt<String> thumbnailUrl(Body b) {
        JsonNode node = b.node("thumbnailUrl");
        if (node == null) return Opt.absent();
        if (node.isNull()) return Opt.of(null);
        if (!node.isString()) {
            b.abort("thumbnailUrl", "Invalid input");
            return Opt.absent();
        }
        String raw = node.stringValue();
        if (raw.isEmpty()) return Opt.of(null);
        if (UPLOAD_URL.matcher(raw).matches()) return Opt.of(raw);
        String trimmed = Body.jsTrim(raw);
        if (isUrl(trimmed)) return Opt.of(trimmed);
        b.fail("thumbnailUrl", BAD_THUMBNAIL);
        return Opt.absent();
    }

    /** z.string().datetime({ offset: true }).optional().nullable(): null hoặc không gửi thì không có giá trị. */
    private static Opt<Instant> publishedAt(Body b, boolean[] unusableOffset) {
        JsonNode node = b.node("publishedAt");
        if (node == null) return Opt.absent();
        if (node.isNull()) return Opt.of(null);
        if (!node.isString()) {
            b.abort("publishedAt", "Expected string, received " + (node.isArray() ? "array" : node.isObject() ? "object" : node.isBoolean() ? "boolean" : "number"));
            return Opt.absent();
        }
        Matcher m = DATETIME.matcher(node.stringValue());
        if (!m.matches()) {
            b.fail("publishedAt", "Invalid datetime");
            return Opt.absent();
        }
        LocalDate date;
        try {
            date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            b.fail("publishedAt", "Invalid datetime");
            return Opt.absent();
        }
        String fraction = m.group(7) == null ? "" : (m.group(7) + "00").substring(0, 3);
        int millis = fraction.isEmpty() ? 0 : Integer.parseInt(fraction);
        LocalDateTime local = date.atTime(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)),
                millis * 1_000_000);
        int offsetSeconds = 0;
        if (m.group(8) != null) {
            int hours = Integer.parseInt(m.group(9));
            int minutes = m.group(10) == null ? 0 : Integer.parseInt(m.group(10));
            if (hours > 23 || minutes > 59) {
                unusableOffset[0] = true;
                return Opt.absent();
            }
            offsetSeconds = (hours * 3600 + minutes * 60) * ("-".equals(m.group(8)) ? -1 : 1);
        }
        return Opt.of(local.toInstant(ZoneOffset.UTC).minusSeconds(offsetSeconds));
    }

    /**
     * Gần đúng {@code new URL(s)} của JavaScript (z.string().url() của zod): phải có scheme; với http, https, ws, wss, ftp phải có
     * máy chủ hợp lệ; scheme khác (mailto:, javascript:, a:b…) thì phần còn lại tuỳ ý.
     */
    static boolean isUrl(String input) {
        String s = input.replaceAll("[\\t\\n\\r]", "");
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) <= ' ') start++;
        while (end > start && s.charAt(end - 1) <= ' ') end--;
        Matcher m = SCHEME.matcher(s.substring(start, end));
        if (!m.matches()) return false;
        String scheme = m.group(1).toLowerCase(java.util.Locale.ROOT);
        if (!SPECIAL_SCHEMES.contains(scheme)) return true;
        String rest = m.group(2);
        int i = 0;
        while (i < rest.length() && (rest.charAt(i) == '/' || rest.charAt(i) == '\\')) i++;
        int stop = i;
        while (stop < rest.length() && "/\\?#".indexOf(rest.charAt(stop)) < 0) stop++;
        String authority = rest.substring(i, stop);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        String host = authority;
        String port = "";
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) return false;
            host = authority.substring(1, close);
            if (!host.matches("[0-9A-Fa-f:.]+")) return false;
            String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) return false;
                port = after.substring(1);
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            }
            for (int k = 0; k < host.length(); k++) {
                if (host.charAt(k) <= ' ' || "#/:<>?@[\\]^|".indexOf(host.charAt(k)) >= 0) return false;
            }
        }
        if (host.isEmpty()) return false;
        if (!port.isEmpty() && (!port.matches("\\d{1,9}") || Integer.parseInt(port) > 65535)) return false;
        return true;
    }
}
