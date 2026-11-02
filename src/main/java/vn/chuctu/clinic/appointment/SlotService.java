package vn.chuctu.clinic.appointment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.chuctu.clinic.common.Rules;
import vn.chuctu.clinic.common.VnTime;

/**
 * Khung giờ khám: quy tắc chọn khung và bộ đếm chỗ (slot_counters). Mỗi khung nhận tối đa 6 phiếu.
 * {@link #reserve} và {@link #release} chạy trong giao dịch của thao tác đặt, huỷ hay đổi giờ gọi chúng, nên một
 * thao tác lỗi giữa chừng tự trả lại chỗ khi giao dịch quay lui.
 */
@Service
public class SlotService {

    public static final String USER = "USER";
    public static final String COUNTER = "COUNTER";

    private final JdbcTemplate jdbc;

    public SlotService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Giữ một chỗ; false nếu khung đã đủ 6 phiếu. Bước 1 tạo dòng đếm nếu chưa có; bước 2, UPDATE có điều kiện
     * {@code count < 6}, tăng đúng một lần và số dòng đổi cho biết đã giữ được chỗ hay chưa (Connector/J trả số dòng
     * khớp nên không dùng số dòng của bước 1).
     *
     * <p>Bước 1 dùng {@code ON DUPLICATE KEY UPDATE count = count} thay cho {@code INSERT IGNORE}: khi dòng đã
     * có, INSERT IGNORE giữ khoá chia sẻ rồi UPDATE nâng lên khoá độc quyền, nên nhiều người đặt cùng khung bị deadlock
     * (đo được ở 9 luồng). ON DUPLICATE KEY UPDATE lấy ngay khoá độc quyền nên các giao dịch cùng khung xếp hàng.
     */
    public boolean reserve(LocalDate date, String start) {
        jdbc.update("INSERT INTO slot_counters (slot_date, start_time, `count`) VALUES (?, ?, 0) "
                + "ON DUPLICATE KEY UPDATE `count` = `count`", date, start);
        return jdbc.update("UPDATE slot_counters SET `count` = `count` + 1 WHERE slot_date = ? AND start_time = ? AND `count` < ?",
                date, start, Rules.SLOT_CAPACITY) == 1;
    }

    /** Nhả một chỗ, không bao giờ xuống dưới 0. */
    public void release(LocalDate date, String start) {
        jdbc.update("UPDATE slot_counters SET `count` = `count` - 1 WHERE slot_date = ? AND start_time = ? AND `count` > 0", date, start);
    }

    /**
     * Lý do một khung giờ không chọn được, hoặc null nếu chọn được (slotTimeIssue của Node).
     * USER: hôm nay tới 30 ngày tới, khung chưa bắt đầu (BR.13, BR.14).
     * COUNTER: hôm nay tới 7 ngày tới, khung chưa kết thúc (tiếp tân đặt hộ được khung đang diễn ra).
     */
    public String timeIssue(LocalDate date, String start, String mode, Instant now) {
        if (!Rules.SLOTS.contains(start)) return "Khung giờ không hợp lệ.";
        LocalDate today = VnTime.today(now);
        long days = ChronoUnit.DAYS.between(today, date);
        boolean counter = COUNTER.equals(mode);
        int maxDays = counter ? Rules.BOOKING_COUNTER_MAX_DAYS : Rules.BOOKING_USER_MAX_DAYS;
        if (days < 0) return "Ngày khám đã qua.";
        if (days > maxDays) return "Chỉ đặt được lịch trong vòng " + maxDays + " ngày tới.";
        if (days == 0) {
            int nowMin = VnTime.minutesOfDay(now);
            int startMin = VnTime.hhmmToMinutes(start);
            if (counter ? startMin + Rules.SLOT_MINUTES <= nowMin : startMin <= nowMin) {
                return counter ? "Khung giờ đã kết thúc." : "Khung giờ đã bắt đầu.";
            }
        }
        return null;
    }

    /** Các khung của một ngày kèm số chỗ đã đặt và lý do không chọn được (getAvailability của Node). */
    public List<Map<String, Object>> availability(LocalDate date, String mode, Instant now) {
        Map<String, Integer> booked = new HashMap<>();
        jdbc.query("SELECT start_time, `count` FROM slot_counters WHERE slot_date = ?",
                rs -> {
                    booked.put(rs.getString(1), rs.getInt(2));
                }, date);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String start : Rules.SLOTS) {
            int count = booked.getOrDefault(start, 0);
            String issue = timeIssue(date, start, mode, now);
            boolean full = count >= Rules.SLOT_CAPACITY;
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("start", start);
            slot.put("end", minutesToHhmm(VnTime.hhmmToMinutes(start) + Rules.SLOT_MINUTES));
            slot.put("booked", count);
            slot.put("capacity", Rules.SLOT_CAPACITY);
            slot.put("remaining", Math.max(0, Rules.SLOT_CAPACITY - count));
            slot.put("available", issue == null && !full);
            slot.put("reason", issue != null ? (issue.contains("bắt đầu") ? "Đã bắt đầu" : issue) : full ? "Đã đủ chỗ" : null);
            out.add(slot);
        }
        return out;
    }

    private static String minutesToHhmm(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
