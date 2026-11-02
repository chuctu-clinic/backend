package vn.chuctu.clinic.patient;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mốc thời gian expectedUpdatedAt của người sửa: z.string().datetime({ offset: true }) của zod 3, đọc ra mili giây
 * như {@code new Date(expected).getTime()}.
 */
public final class Stamp {

    private static final String DATE = "((\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29"
            + "|\\d{4}-((0[13578]|1[02])-(0[1-9]|[12]\\d|3[01])|(0[469]|11)-(0[1-9]|[12]\\d|30)|(02)-(0[1-9]|1\\d|2[0-8])))";
    private static final String TIME = "([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d(\\.\\d+)?)?";
    private static final Pattern DATETIME = Pattern.compile("^" + DATE + "T" + TIME + "(Z|[+-]\\d{2}:?\\d{2})$");
    private static final Pattern PARTS =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?(Z|[+-]\\d{2}:?\\d{2})$");

    private Stamp() {}

    /** Lý do không hợp lệ theo zod, null nếu hợp lệ. */
    public static String problem(String s) {
        return DATETIME.matcher(s).matches() ? null : "Invalid datetime";
    }

    /** Mili giây kể từ epoch, null nếu không đọc được (Date không hợp lệ của JavaScript). */
    public static Long epochMillis(String s) {
        Matcher m = PARTS.matcher(s);
        if (!m.matches()) return null;
        try {
            int millis = m.group(5) == null ? 0 : Integer.parseInt((m.group(5) + "00").substring(0, 3));
            LocalTime time = LocalTime.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    m.group(4) == null ? 0 : Integer.parseInt(m.group(4)), millis * 1_000_000);
            String zone = m.group(6);
            int sign = zone.charAt(0) == '-' ? -1 : 1;
            ZoneOffset offset = "Z".equals(zone) ? ZoneOffset.UTC
                    : ZoneOffset.ofHoursMinutes(sign * Integer.parseInt(zone.substring(1, 3)),
                            sign * Integer.parseInt(zone.substring(zone.length() - 2)));
            return LocalDate.parse(m.group(1)).atTime(time).toInstant(offset).toEpochMilli();
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }
}
