package vn.chuctu.clinic.record;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.chuctu.clinic.security.CurrentUser;

/**
 * Kết quả khám và lịch sử khám của người dùng (BR.20, BR.21), phần kết quả của routes/me.routes.js. Chỉ trả bệnh án thuộc hồ sơ
 * của chính tài khoản; không có thì 404 hoặc danh sách rỗng, không để lộ bệnh án của tài khoản khác. Quyền USER do SecurityConfig.
 */
@RestController
@RequestMapping("/api/me")
public class MeRecordController {

    private final RecordService records;
    private final CurrentUser currentUser;

    public MeRecordController(RecordService records, CurrentUser currentUser) {
        this.records = records;
        this.currentUser = currentUser;
    }

    @GetMapping("/appointments/{id}/result")
    Map<String, Object> result(@PathVariable long id) {
        return Map.of("record", records.getUserResult(currentUser.require(), id));
    }

    @GetMapping("/history")
    Map<String, Object> history(@RequestParam(required = false) String patientId) {
        List<Map<String, Object>> items = records.listUserHistory(currentUser.require(), patientId);
        return Map.of("items", items);
    }
}
