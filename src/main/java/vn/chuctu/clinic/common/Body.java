package vn.chuctu.clinic.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Đọc và kiểm tra JSON gửi lên, thay cho zod trong utils/validate.js. Mỗi ô lỗi được ghi lại (giữ lỗi đầu tiên
 * của từng ô, khoá lồng dùng dấu chấm, lỗi gốc dùng khoá "_"); gọi {@link #check()} sau cùng để ném VALIDATION.
 * Trường lạ bị bỏ qua. Body không phải object (hoặc không có body) được xử lý như zod z.object.
 */
public final class Body {

    /** Quy tắc cho một ô chuỗi bắt buộc: thứ tự trim, biến đổi, độ dài, kiểm tra riêng như chuỗi zod. */
    public record Rule(String requiredMessage, boolean trim, UnaryOperator<String> transform, int min, String minMessage,
            int max, String maxMessage, Function<String, String> problem) {

        public static final Rule PHONE = new Rule("Cô chú vui lòng nhập số điện thoại.", false, Phones::normalize, 0, null,
                Integer.MAX_VALUE, null, s -> Phones.problem(s, true));
        public static final Rule FULL_NAME = new Rule("Cô chú vui lòng nhập họ và tên.", true, UnaryOperator.identity(), 2,
                "Cô chú vui lòng nhập họ và tên.", 100, "Họ và tên quá dài.", null);

        /** z.string().trim().toLowerCase().min(1).email(): regex email của zod 3. */
        public static final Rule EMAIL = new Rule("Vui lòng nhập email.", true, v -> v.toLowerCase(java.util.Locale.ROOT), 1,
                "Vui lòng nhập email.", Integer.MAX_VALUE, null,
                s -> ZOD_EMAIL.matcher(s).matches() ? null : "Email chưa đúng, ví dụ ten@email.com.");

        /** Như EMAIL, thêm giới hạn của cột email: dùng ở nơi ghi email vào database. */
        public static final Rule EMAIL_STORED = new Rule("Vui lòng nhập email.", true, v -> v.toLowerCase(java.util.Locale.ROOT), 1,
                "Vui lòng nhập email.", Integer.MAX_VALUE, null,
                s -> !ZOD_EMAIL.matcher(s).matches() ? "Email chưa đúng, ví dụ ten@email.com." : tooLongEmail(s));

        public static Rule required(String requiredMessage) {
            return new Rule(requiredMessage, false, UnaryOperator.identity(), 0, null, Integer.MAX_VALUE, null, null);
        }

        public Rule trimmed() {
            return new Rule(requiredMessage, true, transform, min, minMessage, max, maxMessage, problem);
        }

        public Rule min(int length, String message) {
            return new Rule(requiredMessage, trim, transform, length, message, max, maxMessage, problem);
        }

        public Rule max(int length, String message) {
            return new Rule(requiredMessage, trim, transform, min, minMessage, length, message, problem);
        }

        /** Kiểm tra riêng sau khi biến đổi: trả về lý do lỗi, null nếu hợp lệ. */
        public Rule problem(Function<String, String> check) {
            return new Rule(requiredMessage, trim, transform, min, minMessage, max, maxMessage, check);
        }
    }

    /**
     * Cột email chứa tối đa 700 ký tự (V2__widen_long_inputs.sql). Zod không giới hạn độ dài nên Node nhận mọi email đúng
     * định dạng; email dài hơn cột thì Java báo lỗi ở ô email thay vì 500.
     */
    public static final int EMAIL_MAX = 700;
    public static final String EMAIL_TOO_LONG = "Email tối đa " + EMAIL_MAX + " ký tự.";

    /** Lý do email quá dài, null nếu vừa cột. Gọi sau khi đã biết email đúng định dạng. */
    public static String tooLongEmail(String email) {
        return email.length() > EMAIL_MAX ? EMAIL_TOO_LONG : null;
    }

    private static final Pattern ZOD_EMAIL = Pattern.compile(
            "^(?!\\.)(?!.*\\.\\.)([A-Z0-9_'+\\-\\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\\-]*\\.)+[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID = Pattern.compile("^[1-9]\\d{0,18}$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final JsonNode root;
    /** false khi body có nhưng không phải object: lỗi gốc đã ghi, không báo thêm từng ô. */
    private final boolean objectBody;
    private final Map<String, String> errors = new LinkedHashMap<>();
    /** zod bỏ qua refine của cả object khi có ô lỗi kiểu (thiếu, sai kiểu, sai enum); lỗi chuỗi hay số thì vẫn chạy refine. */
    private boolean aborted;
    /** Body lồng trong ô của một body khác: lỗi ghi vào body cha với khoá có tiền tố (ví dụ treatmentNotes.other). */
    private final Body parent;
    private final String prefix;

    private Body(JsonNode root, boolean objectBody) {
        this(root, objectBody, null, "");
    }

    private Body(JsonNode root, boolean objectBody, Body parent, String prefix) {
        this.root = root;
        this.objectBody = objectBody;
        this.parent = parent;
        this.prefix = prefix;
    }

    /** null (không có body) coi như object rỗng, giống express.json. */
    public static Body of(JsonNode node) {
        if (node == null) return new Body(null, true);
        Body body = new Body(node.isObject() ? node : null, node.isObject());
        if (!node.isObject()) body.abort("_", "Expected object, received " + typeName(node));
        return body;
    }

    /** Ghi lỗi cho một ô nếu ô đó chưa có lỗi. */
    public void fail(String field, String message) {
        if (parent != null) parent.fail(prefix + field, message);
        else errors.putIfAbsent(field, message);
    }

    /** Như {@link #fail} cho lỗi kiểu (thiếu, sai kiểu, sai enum): zod coi ô đó là hỏng hẳn và không chạy refine của object. */
    public void abort(String field, String message) {
        aborted = true;
        if (parent != null) parent.abort(prefix + field, message);
        else fail(field, message);
    }

    /**
     * Ô là một object con (z.object({...}).optional()): trả về body con để đọc từng ô bên trong, lỗi của ô con ghi vào body này
     * với khoá {@code field.con}. Không gửi hoặc gửi null/sai kiểu: trả null (gửi null hoặc sai kiểu thì ghi lỗi ở chính ô).
     */
    public Body object(String field) {
        JsonNode node = field(field);
        if (node == null) return null;
        if (!node.isObject()) {
            abort(field, "Expected object, received " + typeName(node));
            return null;
        }
        return new Body(node, true, this, field + ".");
    }

    /** Có ô nào hỏng hẳn chưa (xem {@link #abort}). */
    public boolean aborted() {
        return aborted;
    }

    /** Body có phải object không (không phải thì lỗi gốc đã ghi, không báo thêm ô thiếu). */
    public boolean isObject() {
        return objectBody;
    }

    /** Giá trị thô của ô: null nếu không gửi; JSON null là một NullNode. */
    public JsonNode node(String field) {
        return field(field);
    }

    /** String.prototype.trim của JavaScript. */
    public static String jsTrim(String s) {
        return trim(s);
    }

    /** Email đúng theo regex của zod. */
    public static boolean isEmail(String s) {
        return ZOD_EMAIL.matcher(s).matches();
    }

    /** Ném VALIDATION nếu đã có lỗi ở bất kỳ ô nào. */
    public void check() {
        if (!errors.isEmpty()) throw Errors.validation(new LinkedHashMap<>(errors));
    }

    public String requiredString(String field, Rule rule) {
        JsonNode node = field(field);
        if (node == null) {
            if (objectBody) abort(field, rule.requiredMessage());
            return null;
        }
        if (!node.isString()) {
            abort(field, "Expected string, received " + typeName(node));
            return null;
        }
        String s = node.stringValue();
        if (rule.trim()) s = trim(s);
        s = rule.transform().apply(s);
        if (s.length() < rule.min()) fail(field, rule.minMessage());
        else if (s.length() > rule.max()) fail(field, rule.maxMessage());
        else if (rule.problem() != null) {
            String problem = rule.problem().apply(s);
            if (problem != null) fail(field, problem);
        }
        return s;
    }

    /** Không gửi: absent (giữ nguyên). Gửi null hoặc chuỗi rỗng: present(null) (xoá). Chuỗi được trim. */
    public Opt<String> optionalText(String field, int max) {
        JsonNode node = field(field);
        if (node == null) return Opt.absent();
        if (node.isNull()) return Opt.of(null);
        if (!node.isString()) {
            abort(field, "Expected string, received " + typeName(node));
            return Opt.absent();
        }
        String s = trim(node.stringValue());
        if (s.length() > max) fail(field, "Tối đa " + max + " ký tự.");
        return Opt.of(s.isEmpty() ? null : s);
    }

    /** Ngày YYYY-MM-DD có thật. Không gửi: absent. Gửi null: present(null). */
    public Opt<LocalDate> date(String field) {
        JsonNode node = field(field);
        if (node == null) return Opt.absent();
        if (node.isNull()) return Opt.of(null);
        if (!node.isString()) {
            abort(field, "Expected string, received " + typeName(node));
            return Opt.absent();
        }
        String s = node.stringValue();
        if (DATE.matcher(s).matches()) {
            try {
                return Opt.of(LocalDate.parse(s));
            } catch (DateTimeParseException e) {
                // rơi xuống báo lỗi: ví dụ 30/02 không có thật
            }
        }
        fail(field, "Ngày không hợp lệ.");
        return Opt.absent();
    }

    /** Mã là chuỗi số nguyên dương vừa một long. Không gửi: absent. Gửi null: present(null). */
    public Opt<Long> id(String field) {
        JsonNode node = field(field);
        if (node == null) return Opt.absent();
        if (node.isNull()) return Opt.of(null);
        if (!node.isString()) {
            abort(field, "Expected string, received " + typeName(node));
            return Opt.absent();
        }
        String s = node.stringValue();
        if (ID.matcher(s).matches()) {
            try {
                return Opt.of(Long.parseLong(s));
            } catch (NumberFormatException e) {
                // quá lớn: rơi xuống báo lỗi
            }
        }
        fail(field, "Mã không hợp lệ.");
        return Opt.absent();
    }

    /**
     * Ngày YYYY-MM-DD có thật, bắt buộc (z.string().refine(isDateStr)). Thiếu: "Required"; sai kiểu hoặc ngày không có thật:
     * lỗi giống zod. Trả null nếu đã ghi lỗi.
     */
    public LocalDate requiredDate(String field) {
        JsonNode node = field(field);
        if (node == null) {
            if (objectBody) abort(field, "Required");
            return null;
        }
        if (node.isNull()) {
            abort(field, "Expected string, received null");
            return null;
        }
        Opt<LocalDate> date = date(field);
        return date.isPresent() && date.get() != null ? date.get() : null;
    }

    /**
     * z.enum([...]): bắt buộc, giá trị phải nằm trong danh sách. {@code customMessage} (errorMap của zod) thay cho mọi
     * thông báo mặc định của zod; null thì dùng thông báo mặc định.
     */
    public String requiredEnum(String field, List<String> allowed, String customMessage) {
        JsonNode node = field(field);
        String expected = allowed.stream().map(v -> "'" + v + "'").collect(Collectors.joining(" | "));
        if (node == null) {
            if (objectBody) abort(field, customMessage != null ? customMessage : "Required");
            return null;
        }
        if (!node.isString()) {
            abort(field, customMessage != null ? customMessage : "Expected " + expected + ", received " + typeName(node));
            return null;
        }
        String s = node.stringValue();
        if (!allowed.contains(s)) {
            abort(field, customMessage != null ? customMessage : "Invalid enum value. Expected " + expected + ", received '" + s + "'");
            return null;
        }
        return s;
    }

    /**
     * Chuỗi tuỳ chọn, giữ nguyên (không trim), kiểm tra riêng sau khi biết là chuỗi. Không gửi: absent. Gửi null hoặc sai
     * kiểu: lỗi "Expected string" như z.string().optional().
     */
    public Opt<String> optionalString(String field, Function<String, String> problem) {
        JsonNode node = field(field);
        if (node == null) return Opt.absent();
        if (!node.isString()) {
            abort(field, "Expected string, received " + typeName(node));
            return Opt.absent();
        }
        String s = node.stringValue();
        String message = problem.apply(s);
        if (message != null) fail(field, message);
        return Opt.of(s);
    }

    /**
     * z.string()[.trim()].max(max, message).optional(): không gửi là absent, chuỗi rỗng vẫn là chuỗi (khác {@link #optionalText}).
     * Gửi null hoặc sai kiểu: "Expected string, received ...".
     */
    public Opt<String> optionalString(String field, boolean trim, int max, String maxMessage) {
        JsonNode node = field(field);
        if (node == null) return Opt.absent();
        if (!node.isString()) {
            abort(field, "Expected string, received " + typeName(node));
            return Opt.absent();
        }
        String s = trim ? trim(node.stringValue()) : node.stringValue();
        if (s.length() > max) fail(field, maxMessage);
        return Opt.of(s);
    }

    /**
     * z.union([chuỗi có kiểm tra, z.literal(""), z.null()]).optional().transform(v => v || null): không gửi là absent; null hoặc
     * chuỗi rỗng đúng nghĩa là present(null) (xoá); chuỗi khác đi qua {@code prepare} (trim, chuẩn hoá) rồi {@code problem}
     * (trả lý do lỗi đầu tiên, null nếu hợp lệ). Kiểu khác chuỗi: zod báo "Invalid input" vì không nhánh nào khớp.
     */
    public Opt<String> clearableString(String field, UnaryOperator<String> prepare, Function<String, String> problem) {
        JsonNode node = field(field);
        if (node == null) return Opt.absent();
        if (node.isNull()) return Opt.of(null);
        if (!node.isString()) {
            abort(field, "Invalid input");
            return Opt.absent();
        }
        if (node.stringValue().isEmpty()) return Opt.of(null);
        String s = prepare.apply(node.stringValue());
        String message = problem.apply(s);
        if (message != null) fail(field, message);
        return Opt.of(s.isEmpty() ? null : s);
    }

    /** z.boolean(): bắt buộc. */
    public boolean requiredBoolean(String field) {
        JsonNode node = field(field);
        if (node == null) {
            if (objectBody) abort(field, "Required");
            return false;
        }
        if (!node.isBoolean()) {
            abort(field, "Expected boolean, received " + typeName(node));
            return false;
        }
        return node.booleanValue();
    }

    /** z.boolean().optional().default(fallback). */
    public boolean optionalBoolean(String field, boolean fallback) {
        JsonNode node = field(field);
        if (node == null) return fallback;
        if (!node.isBoolean()) {
            abort(field, "Expected boolean, received " + typeName(node));
            return fallback;
        }
        return node.booleanValue();
    }

    private JsonNode field(String name) {
        return root == null ? null : root.get(name);
    }

    /** Tên kiểu như zod báo ("Expected string, received number"). */
    private static String typeName(JsonNode node) {
        if (node.isArray()) return "array";
        if (node.isObject()) return "object";
        if (node.isString()) return "string";
        if (node.isBoolean()) return "boolean";
        if (node.isNull()) return "null";
        return "number";
    }

    /** String.prototype.trim của JavaScript: gồm cả khoảng trắng Unicode, NBSP và BOM. */
    private static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isJsSpace(s.charAt(start))) start++;
        while (end > start && isJsSpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isJsSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '﻿';
    }
}
