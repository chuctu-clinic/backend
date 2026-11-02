package vn.chuctu.clinic.patient;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Khoá và mở khoá hồ sơ bệnh nhân (UC.9.6), phần hồ sơ của routes/admin.routes.js. Quyền ADMIN do SecurityConfig. */
@RestController
@RequestMapping("/api/admin/patients")
public class AdminPatientController {

    private final PatientService patients;

    public AdminPatientController(PatientService patients) {
        this.patients = patients;
    }

    @PostMapping("/{id}/lock")
    Map<String, Object> lock(@PathVariable long id) {
        return Map.of("patient", patients.toPublic(patients.setPatientLock(id, true)));
    }

    @PostMapping("/{id}/unlock")
    Map<String, Object> unlock(@PathVariable long id) {
        return Map.of("patient", patients.toPublic(patients.setPatientLock(id, false)));
    }
}
