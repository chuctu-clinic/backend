package vn.chuctu.clinic.patient;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.chuctu.clinic.common.Constants;

/**
 * Các truy vấn lượt khám của hồ sơ (bảng appointments và medical_records). Entity của hai bảng này thuộc Task 7 và 9
 * nên ở đây đọc thẳng bằng SQL; hai task đó có thể thay bằng repository của mình.
 */
@Component
public class PatientVisitQueries {

    private static final String ACTIVE_LIST = "'" + String.join("','", Constants.ACTIVE_STATUSES) + "'";

    private final JdbcTemplate jdbc;

    public PatientVisitQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ngày khám gần nhất (phiếu đã hoàn thành) của từng hồ sơ: lastVisitDates của patient.service.js. */
    public Map<Long, LocalDate> lastCompletedSlotDates(Collection<Long> patientIds) {
        return lastDates("SELECT patient_id, MAX(slot_date) FROM appointments WHERE status = 'COMPLETED' AND patient_id IN (%s) "
                + "GROUP BY patient_id", patientIds);
    }

    /** Ngày khám gần nhất theo bệnh án của từng hồ sơ: lastVisitByPatient của medicalRecord.service.js. */
    public Map<Long, LocalDate> lastRecordVisitDates(Collection<Long> patientIds) {
        return lastDates("SELECT patient_id, MAX(visit_date) FROM medical_records WHERE patient_id IN (%s) GROUP BY patient_id",
                patientIds);
    }

    private Map<Long, LocalDate> lastDates(String sql, Collection<Long> patientIds) {
        Map<Long, LocalDate> out = new HashMap<>();
        if (patientIds.isEmpty()) return out;
        String marks = String.join(",", patientIds.stream().map(id -> "?").toList());
        jdbc.query(sql.formatted(marks), rs -> {
            out.put(rs.getLong(1), rs.getObject(2, LocalDate.class));
        }, patientIds.toArray());
        return out;
    }

    /** Hồ sơ còn phiếu chưa hoàn thành (đã đặt, check-in hoặc đang khám). */
    public boolean hasActiveAppointment(long patientId) {
        return exists("SELECT EXISTS (SELECT 1 FROM appointments WHERE patient_id = ? AND status IN (" + ACTIVE_LIST + "))", patientId);
    }

    /** Hồ sơ đã có phiếu khám hoặc bệnh án (BR.38): không được xoá cứng. */
    public boolean hasHistory(long patientId) {
        return exists("SELECT EXISTS (SELECT 1 FROM appointments WHERE patient_id = ?) "
                + "OR EXISTS (SELECT 1 FROM medical_records WHERE patient_id = ?)", patientId, patientId);
    }

    private boolean exists(String sql, Object... args) {
        List<Boolean> rows = jdbc.query(sql, (rs, i) -> rs.getBoolean(1), args);
        return !rows.isEmpty() && rows.get(0);
    }
}
