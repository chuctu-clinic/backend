package vn.chuctu.clinic.account;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.patient.Patient;
import vn.chuctu.clinic.patient.PatientForms;
import vn.chuctu.clinic.patient.PatientService;
import vn.chuctu.clinic.security.CurrentUser;

/** Thông tin tài khoản của người dùng (UC.1.4, UC.1.5), phần đầu của routes/me.routes.js. Quyền USER do SecurityConfig. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AccountService accounts;
    private final PatientService patients;
    private final CurrentUser currentUser;
    private final Clock clock;

    public MeController(AccountService accounts, PatientService patients, CurrentUser currentUser, Clock clock) {
        this.accounts = accounts;
        this.patients = patients;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping
    Map<String, Object> get() {
        User user = currentUser.require();
        return view(user, patients.findSelfProfile(user.getId()));
    }

    @PatchMapping
    Map<String, Object> update(@RequestBody(required = false) JsonNode json) {
        User user = currentUser.require();
        Body body = Body.of(json);
        // Số điện thoại là tên đăng nhập nên không tự sửa được (BR.01)
        JsonNode phone = body.node("phone");
        if (phone != null && !(phone.isString() && phone.stringValue().equals(user.getPhone()))) {
            throw Errors.badRequest("Số điện thoại là tên đăng nhập nên không tự sửa được. Cô chú gọi phòng khám nếu cần đổi.");
        }
        var data = PatientForms.account(body, VnTime.today(clock.instant()));
        AccountService.MeUpdate result = accounts.updateMe(user, data);
        return view(result.user(), result.self());
    }

    private static Map<String, Object> view(User user, Patient self) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user.toPublic());
        out.put("patientCode", self == null ? null : self.getPatientCode());
        return out;
    }
}
