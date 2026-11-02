package vn.chuctu.clinic.account;

import java.util.Locale;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Body.Rule;
import vn.chuctu.clinic.common.Constants;
import vn.chuctu.clinic.common.Opt;
import vn.chuctu.clinic.common.Rules;
import vn.chuctu.clinic.patient.PatientForms;

/**
 * Kiểm tra JSON quản lý tài khoản của Admin, thay cho các z.object trong routes/admin.routes.js. Trường lạ bị bỏ qua
 * (zod cắt bỏ), nên PATCH không nhận dob hay gender.
 */
public final class AccountForms {

    /** POST /accounts. email: absent hoặc present(null) đều là không có email. tempPassword null nếu không gửi. */
    public record CreateData(String fullName, String phone, Opt<String> email, String role, String tempPassword) {}

    /** PATCH /accounts/:id: ô nào không gửi thì giữ nguyên. */
    public record UpdateData(Opt<String> fullName, Opt<String> phone, Opt<String> email, Opt<String> address,
            String expectedUpdatedAt) {}

    /** PUT /accounts/:id/role. */
    public record RoleData(String role, String tempPassword, Opt<String> email) {}

    private static final String ROLE_MESSAGE = "Vui lòng chọn vai trò.";
    private static final String TEMP_PASSWORD_MESSAGE = "Mật khẩu tạm cần tối thiểu " + Rules.MIN_PASSWORD_LENGTH + " ký tự.";

    private AccountForms() {}

    public static CreateData create(Body b) {
        String fullName = b.requiredString("fullName", Rule.FULL_NAME);
        String phone = b.requiredString("phone", Rule.PHONE);
        Opt<String> email = email(b);
        String role = b.requiredEnum("role", Constants.ROLES, ROLE_MESSAGE);
        String tempPassword = optionalTempPassword(b);
        b.check();
        return new CreateData(fullName, phone, email, role, tempPassword);
    }

    public static UpdateData update(Body b) {
        Opt<String> fullName = b.node("fullName") == null ? Opt.absent() : Opt.of(b.requiredString("fullName", Rule.FULL_NAME));
        Opt<String> phone = b.node("phone") == null ? Opt.absent() : Opt.of(b.requiredString("phone", Rule.PHONE));
        Opt<String> email = email(b);
        Opt<String> address = PatientForms.accountAddress(b);
        String expectedUpdatedAt = PatientForms.expectedUpdatedAt(b);
        b.check();
        return new UpdateData(fullName, phone, email, address, expectedUpdatedAt);
    }

    public static RoleData role(Body b) {
        String role = b.requiredEnum("role", Constants.ROLES, ROLE_MESSAGE);
        String tempPassword = optionalTempPassword(b);
        Opt<String> email = email(b);
        b.check();
        return new RoleData(role, tempPassword, email);
    }

    public static String resetPassword(Body b) {
        String tempPassword = b.requiredString("tempPassword", Rule.required("Required").min(Rules.MIN_PASSWORD_LENGTH, TEMP_PASSWORD_MESSAGE));
        b.check();
        return tempPassword;
    }

    /**
     * z.union([z.string().trim().toLowerCase().email(), z.literal(''), z.null()]).optional(): không gửi là absent, null hoặc
     * chuỗi rỗng là xoá. Thêm giới hạn độ dài của cột email.
     */
    private static Opt<String> email(Body b) {
        return b.clearableString("email", s -> Body.jsTrim(s).toLowerCase(Locale.ROOT),
                s -> !Body.isEmail(s) ? "Email chưa đúng định dạng." : Body.tooLongEmail(s));
    }

    private static String optionalTempPassword(Body b) {
        return b.optionalString("tempPassword", s -> s.length() < Rules.MIN_PASSWORD_LENGTH ? TEMP_PASSWORD_MESSAGE : null).get();
    }
}
