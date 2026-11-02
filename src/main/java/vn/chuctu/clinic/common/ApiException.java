package vn.chuctu.clinic.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lỗi nghiệp vụ trả về cho client, tương đương AppError trong utils/errors.js. Không ghi stack trace để rẻ khi ném. */
public final class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** {"error":{"code","message",...details}} */
    public Map<String, Object> toBody() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", getMessage());
        if (details != null) error.putAll(details);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }
}
