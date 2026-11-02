package vn.chuctu.clinic.appointment;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.appointment.AppointmentService.BookingRequest;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Body.Rule;
import vn.chuctu.clinic.common.Constants;
import vn.chuctu.clinic.common.Opt;
import vn.chuctu.clinic.common.Rules;

/** Kiểm tra dữ liệu gửi lên của các API phiếu khám, thay cho bookingSchema và các z.object trong routes (me, staff). */
final class AppointmentForms {

    private static final List<String> SERVICE_KEYS = List.copyOf(Constants.SERVICE_TYPES.keySet());
    private static final Pattern MONTH = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final String REASON_REQUIRED = "Vui lòng chọn lý do huỷ.";

    private AppointmentForms() {}

    /** Mã bắt buộc: thiếu báo Required như zod. */
    private static Long requiredId(Body b, String field) {
        JsonNode node = b.node(field);
        if (node == null) {
            if (b.isObject()) b.abort(field, "Required");
            return null;
        }
        if (node.isNull()) {
            b.abort(field, "Expected string, received null");
            return null;
        }
        Opt<Long> id = b.id(field);
        return id.isPresent() ? id.get() : null;
    }

    private static String serviceType(Body b) {
        return b.requiredEnum("serviceType", SERVICE_KEYS, "Cô chú vui lòng chọn nội dung khám.");
    }

    private static String slotStart(Body b) {
        return b.requiredEnum("slotStart", Rules.SLOTS, "Cô chú vui lòng chọn giờ khám.");
    }

    /** bookingSchema: người dùng đặt lịch. */
    static BookingRequest userBooking(Body b) {
        Long patientId = requiredId(b, "patientId");
        String serviceType = serviceType(b);
        LocalDate date = b.requiredDate("slotDate");
        String start = slotStart(b);
        Opt<Long> rebook = b.id("rebookFromId");
        b.check();
        return new BookingRequest(patientId, serviceType, date, start, rebook.get());
    }

    /** Tiếp tân đặt hộ. checkInNow chỉ được kiểm tra dạng; việc check-in ngay là của Task 8. */
    static BookingRequest counterBooking(Body b) {
        Long patientId = requiredId(b, "patientId");
        String serviceType = serviceType(b);
        LocalDate date = b.requiredDate("slotDate");
        String start = slotStart(b);
        b.optionalBoolean("checkInNow", false);
        b.check();
        return new BookingRequest(patientId, serviceType, date, start, null);
    }

    record Slot(LocalDate date, String start) {}

    static Slot reschedule(Body b) {
        LocalDate date = b.requiredDate("slotDate");
        String start = slotStart(b);
        b.check();
        return new Slot(date, start);
    }

    /** Lý do huỷ của người dùng: tuỳ chọn, tối đa 300 ký tự; không gửi, null hay rỗng đều là không có lý do. */
    static String userCancelReason(Body b) {
        Opt<String> reason = b.optionalText("reason", 300);
        b.check();
        return reason.get();
    }

    /** Lý do huỷ của tiếp tân: bắt buộc. */
    static String counterCancelReason(Body b) {
        String reason = b.requiredString("reason", Rule.required(REASON_REQUIRED).trimmed().min(1, REASON_REQUIRED)
                .max(300, "String must contain at most 300 character(s)"));
        b.check();
        return reason;
    }

    // ---------- Tham số trên URL (z.object(...).parse(req.query)) ----------

    private static Body query(String field, String value) {
        var node = JsonMapper.shared().createObjectNode();
        if (value != null) node.put(field, value);
        return Body.of(node);
    }

    static LocalDate dateParam(String value) {
        Body b = query("date", value);
        LocalDate date = b.requiredDate("date");
        b.check();
        return date;
    }

    static YearMonth monthParam(String value) {
        Body b = query("month", value);
        String month = b.requiredString("month", Rule.required("Required").problem(
                s -> MONTH.matcher(s).matches() && validMonth(s) ? null : "Tháng không hợp lệ."));
        b.check();
        return YearMonth.parse(month);
    }

    private static boolean validMonth(String s) {
        try {
            YearMonth.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static String codeParam(String value) {
        Body b = query("code", value);
        String code = b.requiredString("code", Rule.required("Required").trimmed().min(3, "Vui lòng nhập mã phiếu khám."));
        b.check();
        return code;
    }
}
