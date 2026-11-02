package vn.chuctu.clinic.record;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.security.CurrentUser;

/** Phiếu kết quả khám ở khu nội bộ (UC.9.7, UC.9.9), phần bệnh án của routes/staff.routes.js. Quyền do SecurityConfig. */
@RestController
@RequestMapping("/api/staff")
public class StaffRecordController {

    private final RecordService records;
    private final CurrentUser currentUser;

    public StaffRecordController(RecordService records, CurrentUser currentUser) {
        this.records = records;
        this.currentUser = currentUser;
    }

    @PostMapping("/appointments/{id}/record")
    ResponseEntity<Map<String, Object>> create(@PathVariable long id, @RequestBody(required = false) JsonNode json) {
        var data = RecordForms.parse(json);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("record", records.createRecord(currentUser.require(), id, data)));
    }

    @GetMapping("/records/{id}")
    Map<String, Object> get(@PathVariable long id) {
        return Map.of("record", records.getRecord(id));
    }

    @PutMapping("/records/{id}")
    Map<String, Object> update(@PathVariable long id, @RequestBody(required = false) JsonNode json) {
        var data = RecordForms.parse(json);
        return Map.of("record", records.updateRecord(currentUser.require(), id, data));
    }
}
