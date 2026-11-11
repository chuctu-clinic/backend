package vn.chuctu.clinic.record;

import java.time.LocalDate;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Opt;

/**
 * Kiểm tra dữ liệu lập và sửa phiếu kết quả (recordSchema của routes/schemas.js). Ô không gửi là absent (giữ nguyên khi sửa),
 * gửi null hoặc chuỗi rỗng là xoá. Chuỗi được trim.
 */
public final class RecordForms {

    private static final int NOTE_MAX = 2000;
    private static final int SHORT_MAX = 50;
    private static final int NAME_MAX = 100;

    private RecordForms() {}

    public record RecordData(Opt<LocalDate> visitDate, Opt<String> diagnosis, Opt<String> pulse, Opt<String> bloodPressure,
            Opt<String> acupuncture, Opt<String> acupressure, Opt<String> physio, Opt<String> other, Opt<String> medicalHistory,
            Opt<String> advice, Opt<LocalDate> followUpDate, Opt<String> doctorName) {}

    public static RecordData parse(JsonNode json) {
        Body body = Body.of(json);
        Opt<LocalDate> visitDate = body.date("visitDate");
        // dateStr.optional() không nhận null
        if (visitDate.isPresent() && visitDate.get() == null) {
            body.abort("visitDate", "Expected string, received null");
            visitDate = Opt.absent();
        }
        Opt<String> diagnosis = body.optionalText("diagnosis", NOTE_MAX);
        Opt<String> pulse = body.optionalText("pulse", SHORT_MAX);
        Opt<String> bloodPressure = body.optionalText("bloodPressure", SHORT_MAX);
        Opt<String> acupuncture = Opt.absent();
        Opt<String> acupressure = Opt.absent();
        Opt<String> physio = Opt.absent();
        Opt<String> other = Opt.absent();
        Body notes = body.object("treatmentNotes");
        if (notes != null) {
            acupuncture = notes.optionalText("acupuncture", NOTE_MAX);
            acupressure = notes.optionalText("acupressure", NOTE_MAX);
            physio = notes.optionalText("physio", NOTE_MAX);
            other = notes.optionalText("other", NOTE_MAX);
        }
        Opt<String> medicalHistory = body.optionalText("medicalHistory", NOTE_MAX);
        Opt<String> advice = body.optionalText("advice", NOTE_MAX);
        Opt<LocalDate> followUpDate = body.date("followUpDate");
        Opt<String> doctorName = body.optionalText("doctorName", NAME_MAX);
        body.check();
        return new RecordData(visitDate, diagnosis, pulse, bloodPressure, acupuncture, acupressure, physio, other, medicalHistory, advice,
                followUpDate, doctorName);
    }
}
