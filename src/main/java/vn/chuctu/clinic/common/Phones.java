package vn.chuctu.clinic.common;

import java.util.regex.Pattern;

/** Số điện thoại di động Việt Nam, chép từ backend/src/shared/phone.js để báo cùng một lỗi. */
public final class Phones {

    private static final Pattern SEPARATORS = Pattern.compile("(?U)[\\s.\\-()]");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");
    private static final String LENGTH_NOTE = "Số di động gồm 10 chữ số.";

    private Phones() {}

    /** Chuẩn hoá về dạng 0xxxxxxxxx: bỏ khoảng trắng, dấu chấm, gạch; "+84…" hoặc "84…" (11 số) đổi thành "0…". */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = SEPARATORS.matcher(raw).replaceAll("");
        if (s.startsWith("+84")) s = "0" + s.substring(3);
        else if (s.startsWith("84") && s.length() == 11) s = "0" + s.substring(2);
        return s;
    }

    /**
     * Lý do số điện thoại không hợp lệ, null nếu hợp lệ.
     * Hợp lệ: 10 chữ số bắt đầu bằng 03, 05, 07, 08, 09; nhận cả dạng +84.
     */
    public static String problem(String raw, boolean required) {
        String s = normalize(raw == null ? "" : raw);
        if (s.isEmpty()) return required ? "Cô chú vui lòng nhập số điện thoại." : null;
        if (!DIGITS.matcher(s).matches()) return "Số điện thoại chỉ gồm chữ số.";
        // Phần sau số 0 đầu (hoặc sau +84) phải đủ 9 chữ số
        String rest = s.startsWith("0") ? s.substring(1) : s;
        if (rest.length() < 9) return "Số điện thoại còn thiếu " + (9 - rest.length()) + " chữ số. " + LENGTH_NOTE;
        if (rest.length() > 9) return "Số điện thoại bị thừa " + (rest.length() - 9) + " chữ số. " + LENGTH_NOTE;
        if ("35789".indexOf(rest.charAt(0)) < 0) {
            return "Đầu số 0" + rest.charAt(0) + " không phải đầu số di động. Số di động bắt đầu bằng 03, 05, 07, 08 hoặc 09.";
        }
        return null;
    }
}
