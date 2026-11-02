package vn.chuctu.clinic.common;

import java.util.Map;

/** Các lỗi thường dùng, thông báo và mã mặc định giống utils/errors.js. */
public final class Errors {

    public static final String VALIDATION_MESSAGE = "Thông tin chưa hợp lệ, cô chú kiểm tra lại các ô được đánh dấu.";

    private Errors() {}

    public static ApiException badRequest(String message) {
        return badRequest(message, null, "BAD_REQUEST");
    }

    public static ApiException badRequest(String message, Map<String, Object> details, String code) {
        return new ApiException(400, code, message, details);
    }

    /** Lỗi kiểm tra dữ liệu: fields là lỗi đầu tiên của từng ô. */
    public static ApiException validation(Map<String, String> fields) {
        return badRequest(VALIDATION_MESSAGE, Map.of("fields", fields), "VALIDATION");
    }

    public static ApiException unauthorized() {
        return new ApiException(401, "UNAUTHORIZED", "Cô chú vui lòng đăng nhập để tiếp tục.", null);
    }

    public static ApiException forbidden() {
        return forbidden("Tài khoản không có quyền thực hiện thao tác này.");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "FORBIDDEN", message, null);
    }

    public static ApiException notFound() {
        return notFound("Không tìm thấy dữ liệu.");
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message, null);
    }

    public static ApiException conflict(String message, String code) {
        return conflict(message, code, null);
    }

    public static ApiException conflict(String message, String code, Map<String, Object> details) {
        return new ApiException(409, code, message, details);
    }

    public static ApiException tooMany(String message, Map<String, Object> details) {
        return new ApiException(429, "TOO_MANY_REQUESTS", message, details);
    }

    /** JSON gửi lên sai cú pháp (body-parser của Node). */
    public static ApiException badJson() {
        return badRequest("Dữ liệu gửi lên không hợp lệ.", null, "BAD_JSON");
    }

    /** Lỗi không lường trước: không bao giờ kèm chi tiết bên trong. */
    public static ApiException serverError() {
        return new ApiException(500, "SERVER_ERROR", "Hệ thống đang bận, cô chú vui lòng thử lại sau.", null);
    }
}
