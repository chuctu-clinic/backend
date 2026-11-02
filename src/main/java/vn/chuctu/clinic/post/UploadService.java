package vn.chuctu.clinic.post;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import vn.chuctu.clinic.account.User;
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

    /**
     * Đọc tối đa {@value #MAX_BYTES} byte, không bao giờ nhiều hơn {@value #MAX_BYTES} + 1 byte vào bộ nhớ (Content-Length có
     * thể vắng hoặc nói dối). Quá giới hạn thì báo 500 như body-parser của Node (lỗi 413 rơi vào errorHandler).
     */
    public static byte[] readLimited(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BYTES + 1);
        if (data.length > MAX_BYTES) throw Errors.serverError();
        return data;
    }

    public Map<String, Object> saveImage(User admin, String contentType, byte[] data) {
        String type = mediaType(contentType);
        if (!TYPES.contains(type) || data == null || data.length == 0) {
            throw Errors.badRequest("Chỉ nhận ảnh JPG, PNG, WEBP hoặc GIF, tối đa 3 MB.");
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
