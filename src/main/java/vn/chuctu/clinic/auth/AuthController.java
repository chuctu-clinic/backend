package vn.chuctu.clinic.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.auth.AuthService.LoginStart;
import vn.chuctu.clinic.auth.AuthService.RegisterData;
import vn.chuctu.clinic.auth.OtpService.Proof;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Body.Rule;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Rules;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.security.CurrentUser;
import vn.chuctu.clinic.security.SessionService;

/** /api/auth: đăng ký, đăng nhập, đổi mật khẩu, đăng xuất (routes/auth.routes.js). Quên mật khẩu thêm ở Task 4. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");
    private static final List<String> GENDERS = List.of("NAM", "NU", "KHAC");
    private static final List<String> OTP_PURPOSES = List.of("REGISTER", "NEW_DEVICE");
    private static final Rule PASSWORD_REQUIRED = Rule.required("Required");

    private final AuthService auth;
    private final SessionService sessions;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AuthController(AuthService auth, SessionService sessions, CurrentUser currentUser, Clock clock) {
        this.auth = auth;
        this.sessions = sessions;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** Mã xác nhận qua tin nhắn hoặc ID token Firebase, kèm lựa chọn giữ đăng nhập. */
    private record CodeBody(String phone, Proof proof, boolean remember) {}

    private static CodeBody parseCodeBody(JsonNode json) {
        Body body = Body.of(json);
        String phone = body.requiredString("phone", Rule.PHONE);
        String code = body.optionalString("code", s -> SIX_DIGITS.matcher(s).matches() ? null : "Mã xác nhận gồm 6 chữ số.").get();
        String idToken = body.optionalString("idToken", s -> s.length() < 20 ? "String must contain at least 20 character(s)"
                : s.length() > 4096 ? "String must contain at most 4096 character(s)" : null).get();
        boolean remember = body.optionalBoolean("remember", true);
        body.check();
        return new CodeBody(phone, new Proof(code, idToken), remember);
    }

    /** Ghi nhận thiết bị đã xác nhận để lần sau không phải nhập mã (BR.02). */
    private void rememberDevice(String deviceCookie, HttpServletResponse response, User user) {
        String deviceId = deviceCookie;
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = auth.newDeviceId();
            sessions.setDeviceCookie(response, deviceId);
        }
        auth.trustDevice(user.getId(), deviceId);
    }

    @GetMapping("/me")
    Map<String, Object> me() {
        User user = currentUser.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user == null ? null : user.toPublic());
        return out;
    }

    // ---------- Người dùng ----------

    @PostMapping("/register")
    Map<String, Object> register(@RequestBody(required = false) JsonNode json) {
        Body body = Body.of(json);
        String fullName = body.requiredString("fullName", Rule.FULL_NAME);
        String phone = body.requiredString("phone", Rule.PHONE);
        var dob = body.requiredDate("dob");
        if (dob != null && !dob.isBefore(VnTime.today(clock.instant()))) body.fail("dob", "Ngày sinh phải trước hôm nay.");
        String gender = body.requiredEnum("gender", GENDERS, "Cô chú vui lòng chọn giới tính.");
        String address = body.optionalText("address", 300).get();
        body.check();
        return auth.startRegister(new RegisterData(fullName, phone, dob, gender, address));
    }

    @PostMapping("/register/verify")
    ResponseEntity<Map<String, Object>> registerVerify(@RequestBody(required = false) JsonNode json,
            @CookieValue(name = SessionService.DEVICE_COOKIE, required = false) String deviceCookie,
            HttpServletResponse response) {
        CodeBody in = parseCodeBody(json);
        User user = auth.completeRegister(in.phone(), in.proof());
        rememberDevice(deviceCookie, response, user);
        sessions.setSession(response, user, true);
        return ResponseEntity.status(201).body(Map.of("user", user.toPublic()));
    }

    @PostMapping("/login")
    Map<String, Object> login(@RequestBody(required = false) JsonNode json,
            @CookieValue(name = SessionService.DEVICE_COOKIE, required = false) String deviceCookie,
            HttpServletResponse response) {
        Body body = Body.of(json);
        String phone = body.requiredString("phone", Rule.PHONE);
        body.check();
        LoginStart result = auth.startLogin(phone, deviceCookie);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!result.needCode()) {
            sessions.setSession(response, result.user(), true);
            out.put("needCode", false);
            out.put("user", result.user().toPublic());
            return out;
        }
        out.put("needCode", true);
        out.putAll(result.otp());
        return out;
    }

    @PostMapping("/login/verify")
    Map<String, Object> loginVerify(@RequestBody(required = false) JsonNode json,
            @CookieValue(name = SessionService.DEVICE_COOKIE, required = false) String deviceCookie,
            HttpServletResponse response) {
        CodeBody in = parseCodeBody(json);
        User user = auth.completeLogin(in.phone(), in.proof());
        if (in.remember()) rememberDevice(deviceCookie, response, user);
        sessions.setSession(response, user, in.remember());
        return Map.of("user", user.toPublic());
    }

    @PostMapping("/otp/resend")
    Map<String, Object> resend(@RequestBody(required = false) JsonNode json) {
        Body body = Body.of(json);
        String phone = body.requiredString("phone", Rule.PHONE);
        String purpose = body.requiredEnum("purpose", OTP_PURPOSES, null);
        body.check();
        return auth.resendCode(phone, purpose);
    }

    // ---------- Nội bộ ----------

    @PostMapping("/staff/login")
    Map<String, Object> staffLogin(@RequestBody(required = false) JsonNode json, HttpServletResponse response) {
        Body body = Body.of(json);
        String identifier = body.requiredString("identifier",
                Rule.required("Required").trimmed().min(1, "Vui lòng nhập số điện thoại hoặc email."));
        String password = body.requiredString("password", PASSWORD_REQUIRED.min(1, "Vui lòng nhập mật khẩu."));
        body.check();
        User user = auth.staffLogin(identifier, password);
        sessions.setSession(response, user, true);
        return Map.of("user", user.toPublic());
    }

    @PostMapping("/change-password")
    Map<String, Object> changePassword(@RequestBody(required = false) JsonNode json) {
        User current = currentUser.require();
        Body body = Body.of(json);
        String currentPassword = body.requiredString("currentPassword", PASSWORD_REQUIRED.min(1, "Vui lòng nhập mật khẩu hiện tại."));
        String newPassword = body.requiredString("newPassword", PASSWORD_REQUIRED.min(Rules.MIN_PASSWORD_LENGTH,
                "Mật khẩu mới cần tối thiểu " + Rules.MIN_PASSWORD_LENGTH + " ký tự."));
        body.check();
        if (current.getRole().equals("USER")) throw Errors.badRequest("Tài khoản người dùng không dùng mật khẩu.");
        return Map.of("user", auth.changePassword(current.getId(), currentPassword, newPassword).toPublic());
    }

    @PostMapping("/logout")
    Map<String, Object> logout(HttpServletResponse response) {
        sessions.clearSession(response);
        return Map.of("ok", true);
    }
}
