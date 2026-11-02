package vn.chuctu.clinic.patient;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Rules;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.patient.PatientForms.PatientData;
import vn.chuctu.clinic.security.CurrentUser;

/** Hồ sơ khám của người dùng (UC.1.6 đến UC.1.8), phần hồ sơ của routes/me.routes.js. Quyền USER do SecurityConfig. */
@RestController
@RequestMapping("/api/me/patients")
public class MePatientController {

    private final PatientService patients;
    private final CurrentUser currentUser;
    private final Clock clock;

    public MePatientController(PatientService patients, CurrentUser currentUser, Clock clock) {
        this.patients = patients;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    private LocalDate today() {
        return VnTime.today(clock.instant());
    }

    @GetMapping
    Map<String, Object> list() {
        User user = currentUser.require();
        List<Patient> list = patients.listOwnProfiles(user.getId());
        Map<Long, LocalDate> last = patients.lastVisitDates(list.stream().map(Patient::getId).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Patient patient : list) {
            Map<String, Object> item = patients.toPublic(patient);
            // Node trả ngày của phiếu khám nguyên dạng Date (nửa đêm UTC), khác danh sách của tiếp tân
            item.put("lastVisitDate", PatientService.midnightUtc(last.get(patient.getId())));
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("max", Rules.MAX_PROFILES_PER_ACCOUNT);
        return out;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) JsonNode json) {
        User user = currentUser.require();
        PatientData data = PatientForms.profile(Body.of(json), today());
        if ("BAN_THAN".equals(data.relationship().get()) && patients.hasOwnSelfProfile(user.getId())) {
            throw Errors.badRequest("Tài khoản đã có hồ sơ của chính chủ. Cô chú chọn quan hệ khác.",
                    Map.of("fields", Map.of("relationship", "Đã có hồ sơ chính chủ.")), "BAD_REQUEST");
        }
        Patient patient = patients.createOwnProfile(data, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("patient", patients.toPublic(patient)));
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable long id) {
        return Map.of("patient", patients.toPublic(patients.getOwnProfile(currentUser.require().getId(), id)));
    }

    @PatchMapping("/{id}")
    Map<String, Object> update(@PathVariable long id, @RequestBody(required = false) JsonNode json) {
        User user = currentUser.require();
        PatientData data = PatientForms.profileUpdate(Body.of(json), today());
        if ("BAN_THAN".equals(data.relationship().get()) && patients.hasOtherSelfProfile(user.getId(), id)) {
            throw Errors.badRequest("Tài khoản đã có hồ sơ của chính chủ.",
                    Map.of("fields", Map.of("relationship", "Đã có hồ sơ chính chủ.")), "BAD_REQUEST");
        }
        PatientService.OwnProfileUpdate result = patients.updateOwnProfile(user.getId(), id, data);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient", patients.toPublic(result.patient()));
        if (result.user() != null) out.put("user", result.user().toPublic());
        return out;
    }

    @DeleteMapping("/{id}")
    Map<String, Object> delete(@PathVariable long id) {
        return patients.deleteOwnProfile(currentUser.require().getId(), id);
    }
}
