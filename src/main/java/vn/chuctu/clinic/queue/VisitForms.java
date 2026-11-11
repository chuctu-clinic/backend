package vn.chuctu.clinic.queue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Constants;
import vn.chuctu.clinic.common.Opt;

/** Kiểm tra dữ liệu chỉ định điều trị (bác sĩ) và báo cáo điều trị (kỹ thuật viên). Chuỗi được trim, chuỗi rỗng là null. */
public final class VisitForms {

    private static final int NOTE_MAX = 2000;
    private static final int SHORT_MAX = 50;

    private VisitForms() {}

    /** Khám sơ bộ và chỉ định: treatmentKinds là danh sách mã trong Constants.TREATMENT_KINDS, ít nhất một mã. */
    public record OrderData(String symptoms, String prelimDiagnosis, String pulse, String bloodPressure, List<String> treatmentKinds,
            String treatmentOrder, String medicalHistory, String treatmentOther) {

        public String kindsCsv() {
            return String.join(",", treatmentKinds);
        }
    }

    public record ReportData(String acupuncture, String acupressure, String physio, String other, String note) {}

    private static String text(Opt<String> value) {
        return value.get();
    }

    public static OrderData parseOrder(JsonNode json) {
        Body body = Body.of(json);
        String symptoms = text(body.optionalText("symptoms", NOTE_MAX));
        String diagnosis = text(body.optionalText("prelimDiagnosis", NOTE_MAX));
        String pulse = text(body.optionalText("pulse", SHORT_MAX));
        String bloodPressure = text(body.optionalText("bloodPressure", SHORT_MAX));
        String order = text(body.optionalText("treatmentOrder", NOTE_MAX));
        String other = text(body.optionalText("treatmentOther", NOTE_MAX));
        String history = text(body.optionalText("medicalHistory", NOTE_MAX));
        Set<String> kinds = new LinkedHashSet<>();
        JsonNode node = body.node("treatmentKinds");
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String code = item.isString() ? item.stringValue() : null;
                if (code == null || !Constants.TREATMENT_KINDS.containsKey(code)) {
                    body.fail("treatmentKinds", "Kỹ thuật điều trị không hợp lệ.");
                    break;
                }
                kinds.add(code);
            }
        }
        if (kinds.isEmpty() && body.isObject()) body.fail("treatmentKinds", "Vui lòng chọn ít nhất một kỹ thuật điều trị.");
        // Chọn "Khác" thì phải ghi rõ là kỹ thuật gì; không chọn thì bỏ nội dung đã gõ
        if (!kinds.contains("KHAC")) other = null;
        else if (other == null && body.isObject()) body.fail("treatmentOther", "Vui lòng ghi rõ kỹ thuật khác.");
        body.check();
        return new OrderData(symptoms, diagnosis, pulse, bloodPressure, new ArrayList<>(kinds), order, history, other);
    }

    public static ReportData parseReport(JsonNode json) {
        Body body = Body.of(json);
        String acupuncture = text(body.optionalText("acupuncture", NOTE_MAX));
        String acupressure = text(body.optionalText("acupressure", NOTE_MAX));
        String physio = text(body.optionalText("physio", NOTE_MAX));
        String other = text(body.optionalText("other", NOTE_MAX));
        String note = text(body.optionalText("note", NOTE_MAX));
        if (acupuncture == null && acupressure == null && physio == null && other == null && body.isObject()) {
            body.fail("other", "Vui lòng ghi lại ít nhất một việc đã làm khi điều trị.");
        }
        body.check();
        return new ReportData(acupuncture, acupressure, physio, other, note);
    }
}
