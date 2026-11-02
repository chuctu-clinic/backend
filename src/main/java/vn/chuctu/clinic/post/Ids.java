package vn.chuctu.clinic.post;

/** Mã trong đường dẫn công khai: chuỗi toàn chữ số ASCII và vừa một long là mã số, còn lại thì không. */
final class Ids {

    private Ids() {}

    static boolean allDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Mã số, hoặc null nếu không phải toàn chữ số hay quá lớn cho một long. */
    static Long parse(String s) {
        if (!allDigits(s)) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
