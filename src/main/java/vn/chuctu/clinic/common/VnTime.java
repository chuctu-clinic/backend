package vn.chuctu.clinic.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Phòng khám làm việc theo giờ Việt Nam (UTC+7, không có giờ mùa hè). Tương đương backend/src/utils/time.js. */
public final class VnTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DDMM = DateTimeFormatter.ofPattern("ddMM");
    private static final DateTimeFormatter YY = DateTimeFormatter.ofPattern("yy");

    private VnTime() {}

    /** Ngày của một thời điểm theo giờ Việt Nam. */
    public static LocalDate today(Instant now) {
        return now.atZone(ZONE).toLocalDate();
    }

    /** Số phút trong ngày (giờ Việt Nam) của một thời điểm. */
    public static int minutesOfDay(Instant instant) {
        LocalTime time = instant.atZone(ZONE).toLocalTime();
        return time.getHour() * 60 + time.getMinute();
    }

    /** Thời điểm bắt đầu khung giờ (giờ Việt Nam). */
    public static Instant slotInstant(LocalDate date, String hhmm) {
        return date.atStartOfDay(ZONE).plusMinutes(hhmmToMinutes(hhmm)).toInstant();
    }

    public static int hhmmToMinutes(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    /** DDMM của ngày, dùng trong mã phiếu khám. */
    public static String ddmm(LocalDate date) {
        return date.format(DDMM);
    }

    public static String yy(LocalDate date) {
        return date.format(YY);
    }
}
