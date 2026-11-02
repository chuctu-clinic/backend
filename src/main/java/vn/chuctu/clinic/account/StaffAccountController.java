package vn.chuctu.clinic.account;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.auth.AuthService;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Body.Rule;
import vn.chuctu.clinic.security.CurrentUser;

/**
 * Thông tin tài khoản của chính nhân viên (đầu routes/staff.routes.js). Chỉ Tiếp tân và Admin vào được, và chỉ sau khi
 * đã đổi mật khẩu tạm: việc đó do quyền theo URL /api/staff/** trong SecurityConfig đảm nhiệm.
 */
@RestController
@RequestMapping("/api/staff/account")
public class StaffAccountController {

    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");
    private static final Rule CODE = Rule.required("Required")
            .problem(s -> SIX_DIGITS.matcher(s).matches() ? null : "Vui lòng nhập đủ 6 số.");

    private final AuthService auth;
    private final CurrentUser currentUser;

    public StaffAccountController(AuthService auth, CurrentUser currentUser) {
        this.auth = auth;
        this.currentUser = currentUser;
    }

    /** Sửa họ tên. Số điện thoại là tên đăng nhập, vai trò do Admin cấp; đổi email đi qua bước xác minh. */
    @PatchMapping
    Map<String, Object> updateName(@RequestBody(required = false) JsonNode json) {
        Body body = Body.of(json);
        String fullName = body.requiredString("fullName", Rule.FULL_NAME);
        body.check();
        return Map.of("user", auth.updateFullName(currentUser.require(), fullName).toPublic());
    }

    /** Gửi mã tới email mới, nhập đúng mã thì mới lưu. */
    @PostMapping("/email")
    Map<String, Object> startEmailChange(@RequestBody(required = false) JsonNode json) {
        Body body = Body.of(json);
        String email = body.requiredString("email", Rule.EMAIL_STORED);
        body.check();
        return auth.startEmailChange(currentUser.require(), email);
    }

    @PostMapping("/email/verify")
    Map<String, Object> confirmEmailChange(@RequestBody(required = false) JsonNode json) {
        Body body = Body.of(json);
        String code = body.requiredString("code", CODE);
        body.check();
        return Map.of("user", auth.confirmEmailChange(currentUser.require(), code).toPublic());
    }
}
