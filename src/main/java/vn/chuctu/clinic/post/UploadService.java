package vn.chuctu.clinic.post;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.common.ApiException;
import vn.chuctu.clinic.common.Errors;

/** Ảnh cho bài viết (services/upload.service.js): gửi thẳng nội dung ảnh, tối đa 3 MB. */
@Service
public class UploadService {

    /** express.raw({ limit: '3mb' }). */
    public static final int MAX_BYTES = 3 * 1024 * 1024;
    static final List<String> TYPES = List.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final UploadRepository uploads;

    public UploadService(UploadRepository uploads) {
        this.uploads = uploads;
    }

    /** Loại nội dung không kèm tham số (trước dấu chấm phẩy), đã bỏ khoảng trắng; giữ nguyên chữ hoa chữ thường. */
    static String mediaType(String contentType) {
        if (contentType == null) return "";
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
    }

    /**
     * express.raw chỉ đọc body khi loại nội dung (so không phân biệt hoa thường) là một trong bốn loại ảnh; loại khác bị bỏ
     * qua mà không đọc, nên endpoint thấy body rỗng và báo 400.
     */
    public static boolean readsBody(String contentType) {
        return TYPES.contains(mediaType(contentType).toLowerCase(Locale.ROOT));
    }

    static final String TOO_LARGE = "Ảnh quá lớn, vui lòng chọn ảnh tối đa 3 MB.";
    static final String BAD_TYPE = "Chỉ nhận ảnh JPG, PNG, WEBP hoặc GIF, tối đa 3 MB.";

    /**
     * Đọc tối đa {@value #MAX_BYTES} byte, không bao giờ nhiều hơn {@value #MAX_BYTES} + 1 byte vào bộ nhớ (Content-Length có
     * thể vắng hoặc nói dối). Quá giới hạn thì báo 413 kèm giới hạn 3 MB (bản Node báo 500, sửa lỗi A7).
     */
    public static byte[] readLimited(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BYTES + 1);
        if (data.length > MAX_BYTES) {
            throw new ApiException(413, "PAYLOAD_TOO_LARGE", TOO_LARGE, Map.of("maxBytes", MAX_BYTES));
        }
        return data;
    }

    /** Nội dung tệp có đúng là loại ảnh đã khai không, theo chữ ký đầu tệp (không chỉ tin header Content-Type). */
    static boolean matchesSignature(String type, byte[] d) {
        return switch (type) {
            case "image/jpeg" -> d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
            case "image/png" -> startsWith(d, 0, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case "image/gif" -> startsWith(d, 0, "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    || startsWith(d, 0, "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            case "image/webp" -> startsWith(d, 0, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    && startsWith(d, 8, "WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            default -> false;
        };
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[offset + i] != prefix[i]) return false;
        return true;
    }

    public Map<String, Object> saveImage(User admin, String contentType, byte[] data) {
        String type = mediaType(contentType);
        if (!TYPES.contains(type) || data == null || data.length == 0 || !matchesSignature(type, data)) {
            throw Errors.badRequest(BAD_TYPE);
        }
        Upload upload = new Upload();
        upload.setContentType(type);
        upload.setSize(data.length);
        upload.setData(data);
        upload.setCreatedBy(admin.getId());
        upload = uploads.save(upload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(upload.getId()));
        out.put("url", "/api/uploads/" + upload.getId());
        return out;
    }

    /** Mã toàn chữ số vừa một long mới là mã ảnh; còn lại (chữ, quá lớn) không có ảnh nào: 404. */
    public Upload getImage(String id) {
        Long key = Ids.parse(id);
        return (key == null ? java.util.Optional.<Upload>empty() : uploads.findById(key))
                .orElseThrow(() -> Errors.notFound("Không tìm thấy ảnh."));
    }
}
