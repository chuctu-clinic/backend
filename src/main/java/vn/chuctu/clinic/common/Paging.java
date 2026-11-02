package vn.chuctu.clinic.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Phân trang như paging() trong utils/validate.js: page tối thiểu 1, limit từ 1 đến 100. */
public record Paging(int page, int limit, long offset) {

    private static final Pattern LEADING_INT = Pattern.compile("^\\s*([+-]?\\d+)");

    public static Paging of(String page, String limit, int defaultLimit) {
        int rawPage = orZero(page);
        int rawLimit = orZero(limit);
        int p = Math.max(1, rawPage == 0 ? 1 : rawPage);
        int l = Math.min(100, Math.max(1, rawLimit == 0 ? defaultLimit : rawLimit));
        return new Paging(p, l, (long) (p - 1) * l);
    }

    /** Number.parseInt(s, 10) || 0: lấy các chữ số ở đầu chuỗi, không đọc được thì 0, số quá lớn thì chặn ở giới hạn int. */
    private static int orZero(String s) {
        if (s == null) return 0;
        Matcher m = LEADING_INT.matcher(s);
        if (!m.find()) return 0;
        String digits = m.group(1);
        try {
            return Integer.parseInt(digits.startsWith("+") ? digits.substring(1) : digits);
        } catch (NumberFormatException e) {
            return digits.startsWith("-") ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
    }
}
