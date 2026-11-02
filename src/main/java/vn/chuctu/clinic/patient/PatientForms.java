package vn.chuctu.clinic.patient;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Body.Rule;
import vn.chuctu.clinic.common.Constants;
import vn.chuctu.clinic.common.Opt;
import vn.chuctu.clinic.common.Phones;

/**
 * Kiểm tra JSON hồ sơ khám, thay cho profileSchema, profileUpdateSchema, staffPatientSchema, staffPatientUpdateSchema và
 * accountUpdateSchema của routes/schemas.js. Mỗi ô là Opt: không gửi (giữ nguyên), gửi null hoặc rỗng (xoá), hoặc có giá trị.
 */
public final class PatientForms {

    /** Dữ liệu một hồ sơ; ô không có trong schema luôn là absent. */
    public record PatientData(Opt<String> fullName, Opt<String> phone, Opt<LocalDate> dob, Opt<Integer> birthYear,
            Opt<String> gender, Opt<String> relationship, Opt<String> address, Opt<String> nationalId, Opt<String> email,
            String expectedUpdatedAt) {}

    /** Dữ liệu sửa thông tin tài khoản ở trang Thông tin tài khoản. */
    public record AccountData(Opt<String> fullName, Opt<LocalDate> dob, Opt<String> gender, Opt<String> address,
            String expectedUpdatedAt) {}

    private static final List<String> GENDER_KEYS = List.copyOf(Constants.GENDERS.keySet());
    private static final List<String> RELATIONSHIP_KEYS = List.copyOf(Constants.RELATIONSHIPS.keySet());
    private static final String GENDER_MESSAGE = "Cô chú vui lòng chọn giới tính.";
    private static final String RELATIONSHIP_MESSAGE = "Cô chú vui lòng chọn quan hệ với người đi khám.";
    private static final String BIRTH_YEAR_MESSAGE = "Năm sinh chưa đúng.";
    private static final String ADDRESS_REQUIRED = "Vui lòng nhập địa chỉ.";
    private static final Pattern NATIONAL_ID = Pattern.compile("^\\d{9}$|^\\d{12}$");
    private static final Pattern DECIMAL = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    private PatientForms() {}

    /** profileSchema: hồ sơ người dùng tự lập, cần ngày sinh hoặc ít nhất năm sinh. */
    public static PatientData profile(Body b, LocalDate today) {
        Opt<String> fullName = fullName(b, false);
        Opt<String> phone = clearablePhone(b);
        Opt<LocalDate> dob = dob(b, today);
        Opt<Integer> birthYear = birthYear(b, today, false, true);
        Opt<String> gender = enumField(b, "gender", GENDER_KEYS, GENDER_MESSAGE, true, false);
        Opt<String> relationship = enumField(b, "relationship", RELATIONSHIP_KEYS, RELATIONSHIP_MESSAGE, true, false);
        PatientData data = new PatientData(fullName, phone, dob, birthYear, gender, relationship, b.optionalText("address", 300),
                nationalId(b), email(b), expectedUpdatedAt(b));
        // refine của zod chỉ chạy khi không có ô nào hỏng hẳn
        if (!b.aborted() && !truthy(dob) && !truthy(birthYear)) {
            b.fail("dob", "Cô chú vui lòng chọn ngày sinh hoặc năm sinh.");
        }
        b.check();
        return data;
    }

    /** profileUpdateSchema = profileFields.partial(). */
    public static PatientData profileUpdate(Body b, LocalDate today) {
        PatientData data = new PatientData(fullName(b, true), clearablePhone(b), dob(b, today), birthYear(b, today, false, true),
                enumField(b, "gender", GENDER_KEYS, GENDER_MESSAGE, false, false),
                enumField(b, "relationship", RELATIONSHIP_KEYS, RELATIONSHIP_MESSAGE, false, false),
                b.optionalText("address", 300), nationalId(b), email(b), expectedUpdatedAt(b));
        b.check();
        return data;
    }

    /** staffPatientSchema: tiếp tân tạo hồ sơ, bắt buộc họ tên, năm sinh, số điện thoại (BR.25). */
    public static PatientData staffCreate(Body b, LocalDate today) {
        return staff(b, today, false);
    }

    /** staffPatientUpdateSchema = staffPatientSchema.partial(). */
    public static PatientData staffUpdate(Body b, LocalDate today) {
        return staff(b, today, true);
    }

    private static PatientData staff(Body b, LocalDate today, boolean partial) {
        Opt<String> fullName = fullName(b, partial);
        Opt<String> phone = partial && b.node("phone") == null ? Opt.absent() : Opt.of(b.requiredString("phone", Rule.PHONE));
        Opt<Integer> birthYear = birthYear(b, today, !partial, false);
        PatientData data = new PatientData(fullName, phone, dob(b, today), birthYear,
                enumField(b, "gender", GENDER_KEYS, GENDER_MESSAGE, false, true), Opt.absent(),
                b.optionalText("address", 300), nationalId(b), email(b), expectedUpdatedAt(b));
        b.check();
        return data;
    }

    /** accountUpdateSchema: ngày sinh, giới tính, địa chỉ là bắt buộc nếu gửi lên, không xoá trắng được. */
    public static AccountData account(Body b, LocalDate today) {
        Opt<String> fullName = fullName(b, true);
        Opt<LocalDate> dob = Opt.absent();
        JsonNode dobNode = b.node("dob");
        if (dobNode != null) {
            if (!dobNode.isString() || dobNode.stringValue().isEmpty()) {
                b.fail("dob", "Vui lòng chọn đủ ngày, tháng, năm sinh.");
            } else {
                dob = dob(b, today);
            }
        }
        Opt<String> gender = enumField(b, "gender", GENDER_KEYS, GENDER_MESSAGE, false, false);
        Opt<String> address = accountAddress(b);
        AccountData data = new AccountData(fullName, dob, gender, address, expectedUpdatedAt(b));
        b.check();
        return data;
    }

    /** accountUpdateSchema.shape.address: nếu gửi lên thì phải có giá trị, không xoá trắng được. */
    public static Opt<String> accountAddress(Body b) {
        JsonNode addressNode = b.node("address");
        if (addressNode == null) return Opt.absent();
        if (!addressNode.isString()) {
            b.abort("address", ADDRESS_REQUIRED);
            return Opt.absent();
        }
        String s = Body.jsTrim(addressNode.stringValue());
        if (s.isEmpty()) b.fail("address", ADDRESS_REQUIRED);
        else if (s.length() > 300) b.fail("address", "Tối đa 300 ký tự.");
        return Opt.of(s);
    }

    /** Mốc expectedUpdatedAt của người sửa (không gửi: null). */
    public static String expectedUpdatedAt(Body b) {
        return b.optionalString("expectedUpdatedAt", Stamp::problem).get();
    }

    // ---------- Từng ô ----------

    private static boolean truthy(Opt<?> value) {
        return value.isPresent() && value.get() != null;
    }

    /** fullName bắt buộc; partial thì được bỏ qua nhưng gửi null vẫn lỗi. */
    private static Opt<String> fullName(Body b, boolean partial) {
        if (partial && b.node("fullName") == null) return Opt.absent();
        return Opt.of(b.requiredString("fullName", Rule.FULL_NAME));
    }

    /** Số di động hợp lệ, hoặc rỗng hay null để xoá (union của zod); kiểu khác báo "Invalid input". */
    private static Opt<String> clearablePhone(Body b) {
        return b.clearableString("phone", Phones::normalize, s -> Phones.problem(s, true));
    }

    private static Opt<String> nationalId(Body b) {
        return b.clearableString("nationalId", Body::jsTrim,
                s -> NATIONAL_ID.matcher(s).matches() ? null : "Số căn cước gồm 12 chữ số (hoặc 9 số với CMND cũ).");
    }

    private static Opt<String> email(Body b) {
        Opt<String> email = b.clearableString("email", Body::jsTrim, s -> !Body.isEmail(s) ? "Email chưa đúng định dạng."
                : s.length() > 200 ? "String must contain at most 200 character(s)" : null);
        return truthy(email) ? Opt.of(email.get().toLowerCase(Locale.ROOT)) : email;
    }

    /** Ngày có thật và trước hôm nay; không gửi là absent, null là xoá. */
    private static Opt<LocalDate> dob(Body b, LocalDate today) {
        Opt<LocalDate> dob = b.date("dob");
        if (truthy(dob) && !dob.get().isBefore(today)) b.fail("dob", "Ngày sinh phải trước hôm nay.");
        return dob;
    }

    /**
     * z.coerce.number().int().min(1900).max(năm nay). Không đọc ra số (kể cả thiếu khi bắt buộc): "Vui lòng chọn năm sinh.".
     * Null: nullable thì xoá, còn lại Number(null) = 0 nên báo năm sinh chưa đúng.
     */
    private static Opt<Integer> birthYear(Body b, LocalDate today, boolean required, boolean nullable) {
        JsonNode node = b.node("birthYear");
        if (node == null) {
            if (required && b.isObject()) b.abort("birthYear", "Vui lòng chọn năm sinh.");
            return Opt.absent();
        }
        if (node.isNull() && nullable) return Opt.of(null);
        double value = coerceNumber(node);
        if (Double.isNaN(value)) {
            b.abort("birthYear", "Vui lòng chọn năm sinh.");
            return Opt.absent();
        }
        if (Double.isInfinite(value) || value != Math.floor(value) || value < 1900 || value > today.getYear()) {
            b.fail("birthYear", BIRTH_YEAR_MESSAGE);
            // lỗi số chỉ là "dirty" trong zod: giá trị vẫn còn nên refine của hồ sơ không báo thiếu ngày sinh
            return Opt.of(0);
        }
        return Opt.of((int) value);
    }

    /** Number(giá trị) của JavaScript cho số, chuỗi, boolean và null; kiểu khác là NaN. */
    private static double coerceNumber(JsonNode node) {
        if (node.isNumber()) return node.doubleValue();
        if (node.isNull()) return 0;
        if (node.isBoolean()) return node.booleanValue() ? 1 : 0;
        if (!node.isString()) return Double.NaN;
        String s = Body.jsTrim(node.stringValue());
        if (s.isEmpty()) return 0;
        if (s.equals("Infinity") || s.equals("+Infinity")) return Double.POSITIVE_INFINITY;
        if (s.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
        return DECIMAL.matcher(s).matches() ? Double.parseDouble(s) : Double.NaN;
    }

    /** z.enum([...]) bắt buộc (required) hoặc tuỳ chọn; nullable thì gửi null là xoá. */
    private static Opt<String> enumField(Body b, String field, List<String> allowed, String message, boolean required,
            boolean nullable) {
        JsonNode node = b.node(field);
        if (node == null) {
            if (required) b.requiredEnum(field, allowed, message);
            return Opt.absent();
        }
        if (node.isNull() && nullable) return Opt.of(null);
        return Opt.of(b.requiredEnum(field, allowed, message));
    }
}
