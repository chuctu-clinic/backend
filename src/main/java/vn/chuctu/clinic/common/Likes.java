package vn.chuctu.clinic.common;

/** Mẫu LIKE an toàn từ chuỗi người dùng gõ (thay cho escapeRegex): ký tự đặc biệt được hiểu đúng nghĩa đen. */
public final class Likes {

    private Likes() {}

    /** Escape \, % và _ rồi bọc % hai đầu, dùng với dấu escape mặc định của MySQL. */
    public static String contains(String s) {
        return "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
