package vn.chuctu.clinic.patient;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.appointment.AppointmentService;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Paging;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.patient.PatientForms.PatientData;
import vn.chuctu.clinic.record.RecordService;
import vn.chuctu.clinic.security.CurrentUser;

/** Hồ sơ bệnh nhân của Tiếp tân và Admin (UC.9.1 đến UC.9.5, UC.9.8), phần hồ sơ của routes/staff.routes.js. */
@RestController
@RequestMapping("/api/staff/patients")
public class StaffPatientController {

    private static final Pattern ID = Pattern.compile("^[1-9]\\d{0,18}$");

    private final PatientService patients;
    private final AppointmentService appointments;
    private final RecordService records;
    private final CurrentUser currentUser;
    private final Clock clock;

    public StaffPatientController(PatientService patients, AppointmentService appointments, RecordService records, CurrentUser currentUser,
            Clock clock) {
        this.records = records;
        this.patients = patients;
        this.appointments = appointments;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    private LocalDate today() {
        return VnTime.today(clock.instant());
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) String locked,
            @RequestParam(required = false) String page, @RequestParam(required = false) String limit) {
        Paging paging = Paging.of(page, limit, 20);
        PatientService.PatientPage result = patients.searchPatients(q, locked, paging.page(), paging.limit());
        Map<Long, LocalDate> lastVisits = patients.lastRecordDates(result.items().stream().map(Patient::getId).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Patient patient : result.items()) {
            Map<String, Object> item = patients.toPublic(patient);
            LocalDate last = lastVisits.get(patient.getId());
            item.put("lastVisitDate", last == null ? null : last.toString());
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", result.total());
        out.put("page", paging.page());
        out.put("limit", paging.limit());
        return out;
    }

    @GetMapping("/duplicates")
    Map<String, Object> duplicates(@RequestParam(required = false) String phone, @RequestParam(required = false) String excludeId) {
        Long exclude = null;
        if (excludeId != null && !excludeId.isEmpty()) {
            // Mã không đúng dạng là CastError của Mongoose: 404
            if (!ID.matcher(excludeId).matches()) throw Errors.notFound();
            try {
                exclude = Long.parseLong(excludeId);
            } catch (NumberFormatException e) {
                throw Errors.notFound();
            }
        }
        return Map.of("items", patients.findDuplicatesByPhone(phone, exclude).stream().map(patients::toPublic).toList());
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) JsonNode json) {
        Body body = Body.of(json);
        PatientData data = PatientForms.staffCreate(body, today());
        JsonNode confirm = body.node("confirmDuplicate");
        boolean confirmDuplicate = confirm != null && confirm.isBoolean() && confirm.booleanValue();
        Patient patient = patients.staffCreatePatient(data, currentUser.require(), confirmDuplicate);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("patient", patients.toPublic(patient)));
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable long id) {
        Patient patient = patients.getPatient(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient", patients.toPublic(patient));
        out.put("appointments", appointments.listPatientAppointments(patient.getId()));
        out.put("records", records.listPatientRecords(patient.getId()));
        return out;
    }

    @PatchMapping("/{id}")
    Map<String, Object> update(@PathVariable long id, @RequestBody(required = false) JsonNode json) {
        PatientData data = PatientForms.staffUpdate(Body.of(json), today());
        return Map.of("patient", patients.toPublic(patients.staffUpdatePatient(id, data)));
    }
}
