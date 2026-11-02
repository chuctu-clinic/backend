package vn.chuctu.clinic.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hằng số dùng chung, chép từ backend/src/shared/constants.js. Map giữ đúng thứ tự khai báo (như Object.keys). */
public final class Constants {

    private Constants() {}

    public record ReceivingHours(String open, String close, String label, String noShowCancel) {}

    public static final List<String> ROLES = List.of("USER", "RECEPTIONIST", "ADMIN");
    public static final List<String> STAFF_ROLES = List.of("RECEPTIONIST", "ADMIN");
    public static final Map<String, String> ROLE_LABELS =
            ordered("USER", "Người dùng", "RECEPTIONIST", "Tiếp tân", "ADMIN", "Quản trị viên");

    public static final Map<String, String> GENDERS = ordered("NAM", "Nam", "NU", "Nữ", "KHAC", "Khác");

    public static final Map<String, String> RELATIONSHIPS = ordered(
            "BAN_THAN", "Bản thân",
            "VO_CHONG", "Vợ / chồng",
            "CON", "Con",
            "CHA_ME", "Cha / mẹ",
            "ONG_BA", "Ông / bà",
            "ANH_CHI_EM", "Anh / chị / em",
            "KHAC", "Khác");

    public static final Map<String, String> SERVICE_TYPES = ordered(
            "CHUA_RO", "Chưa rõ — nhờ bác sĩ khám và chỉ định",
            "CHAM_CUU", "Châm cứu",
            "VAT_LY_TRI_LIEU", "Vật lý trị liệu — phục hồi chức năng",
            "XOA_BOP_BAM_HUYET", "Xoa bóp — bấm huyệt",
            "BOC_THUOC", "Bốc thuốc — thuốc Nam, thuốc Bắc");

    /** Trạng thái phiếu khám. Người dùng chỉ thấy Đã đặt lịch, Đã hoàn thành, Đã huỷ (BR.17). */
    public static final List<String> APPOINTMENT_STATUSES = List.of("BOOKED", "CHECKED_IN", "IN_PROGRESS", "COMPLETED", "CANCELLED");
    public static final List<String> ACTIVE_STATUSES = List.of("BOOKED", "CHECKED_IN", "IN_PROGRESS");

    /** Giờ nhận bệnh (giờ Việt Nam), tất cả các ngày. Từ noShowCancel, phiếu trong ngày còn Chưa đến tự chuyển sang Đã huỷ. */
    public static final ReceivingHours RECEIVING_HOURS = new ReceivingHours("08:00", "16:30", "8:00 – 16:30", "17:00");

    public static final Map<String, String> STATUS_LABELS = ordered(
            "BOOKED", "Chưa đến",
            "CHECKED_IN", "Đã check-in",
            "IN_PROGRESS", "Đã vào khám",
            "COMPLETED", "Đã hoàn thành",
            "CANCELLED", "Đã huỷ");
    public static final Map<String, String> USER_STATUS_LABELS =
            ordered("BOOKED", "Đã đặt lịch", "COMPLETED", "Đã hoàn thành", "CANCELLED", "Đã huỷ");

    private static Map<String, String> ordered(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
        return Collections.unmodifiableMap(map);
    }
}
