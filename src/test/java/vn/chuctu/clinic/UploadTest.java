package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.chuctu.clinic.TestClient.body;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.chuctu.clinic.common.ApiException;
import vn.chuctu.clinic.post.UploadService;

/** Tải ảnh lên (express.raw, giới hạn 3 MB) và đọc ảnh công khai. Response lỗi đã lấy từ bản Node chạy thật. */
class UploadTest extends IntegrationTest {

    private static final int MAX = 3 * 1024 * 1024;
    private static final String TOO_BIG_BODY = "{\"error\":{\"code\":\"SERVER_ERROR\",\"message\":\"Hệ thống đang bận, cô chú vui lòng thử lại sau.\"}}";
    private static final String BAD_TYPE_BODY = "{\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"Chỉ nhận ảnh JPG, PNG, WEBP hoặc GIF, tối đa 3 MB.\"}}";

    private TestClient admin() {
        return client().staff("admin@chuctu.vn");
    }

    private static byte[] bytes(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }

    private int uploads() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM uploads", Integer.class);
    }

    /** Luồng byte sinh tại chỗ, không giữ trong bộ nhớ, đếm số byte đã bị đọc. */
    private static InputStream generated(long size, AtomicLong served) {
        return new InputStream() {
            private long left = size;

            @Override
            public int read() {
                if (left <= 0) return -1;
                left--;
                served.incrementAndGet();
                return 7;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left <= 0) return -1;
                int n = (int) Math.min(len, left);
                java.util.Arrays.fill(b, off, off + n, (byte) 7);
                left -= n;
                served.addAndGet(n);
                return n;
            }
        };
    }

    @Test
    @DisplayName("[java] ảnh dưới 3 MB lưu và đọc lại đúng byte, kèm Content-Type và Cache-Control")
    void anhDuoi3MbLuuVaDocLaiDungByte() {
        var admin = admin();
        for (String type : List.of("image/jpeg", "image/png", "image/webp", "image/gif")) {
            byte[] data = bytes(200_000);
            var res = admin.sendBytes("POST", "/api/admin/uploads", data, type).expectStatus(201);
            String url = res.json().get("url").stringValue();
            assertThat(url).isEqualTo("/api/uploads/" + res.json().get("id").stringValue());
            assertThat(res.headers().firstValue("content-type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));

            var img = client().getBytes(url);
            assertThat(img.status()).isEqualTo(200);
            assertThat(img.headers().firstValue("content-type")).contains(type);
            assertThat(img.headers().firstValue("cache-control")).contains("public, max-age=31536000, immutable");
            assertThat(img.body()).isEqualTo(data);
            assertThat(jdbc.queryForObject("SELECT size FROM uploads WHERE id = ?", Integer.class, res.json().get("id").stringValue()))
                    .isEqualTo(data.length);
        }
        // Tham số sau Content-Type không làm mất ảnh: lưu loại ảnh thuần
        var withCharset = admin.sendBytes("POST", "/api/admin/uploads", bytes(10), "image/png; charset=x").expectStatus(201);
        assertThat(client().getBytes(withCharset.json().get("url").stringValue()).headers().firstValue("content-type")).contains("image/png");
    }

    @Test
    @DisplayName("[java] đúng 3 MB nhận được, cả khi có Content-Length lẫn khi gửi chunked")
    void dung3MbNhanDuoc() {
        var admin = admin();
        byte[] data = bytes(MAX);
        var sized = admin.sendBytes("POST", "/api/admin/uploads", data, "image/png").expectStatus(201);
        assertThat(client().getBytes(sized.json().get("url").stringValue()).body()).isEqualTo(data);
        var chunked = admin.sendChunked("POST", "/api/admin/uploads", new java.io.ByteArrayInputStream(data), "image/png").expectStatus(201);
        assertThat(client().getBytes(chunked.json().get("url").stringValue()).body()).isEqualTo(data);
    }

    @Test
    @DisplayName("[java] ảnh trên 3 MB trả 500 SERVER_ERROR như Node, có hay không có Content-Length, và không lưu gì")
    void anhTren3MbTraLoiNhuNode() {
        var admin = admin();
        var sized = admin.sendBytes("POST", "/api/admin/uploads", bytes(MAX + 1), "image/png");
        assertThat(sized.status()).isEqualTo(500);
        assertThat(sized.text()).isEqualTo(TOO_BIG_BODY);

        var big = admin.sendBytes("POST", "/api/admin/uploads", bytes(4 * 1024 * 1024), "image/jpeg; charset=x");
        assertThat(big.status()).isEqualTo(500);
        assertThat(big.text()).isEqualTo(TOO_BIG_BODY);

        // Không có Content-Length (chunked): không thể biết trước, server phải tự chặn
        var chunked = admin.sendChunked("POST", "/api/admin/uploads", generated(MAX + 1, new AtomicLong()), "image/png");
        assertThat(chunked.status()).isEqualTo(500);
        assertThat(chunked.text()).isEqualTo(TOO_BIG_BODY);
        assertThat(uploads()).isZero();
    }

    @Test
    @DisplayName("[java] luồng chunked rất dài chỉ bị đọc tối đa 3 MB + 1 byte, không đọc hết vào bộ nhớ")
    void khongDocVoHanVaoBoNho() throws IOException {
        AtomicLong served = new AtomicLong();
        assertThatThrownBy(() -> UploadService.readLimited(generated(Long.MAX_VALUE / 2, served)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(500));
        assertThat(served.get()).isLessThanOrEqualTo(MAX + 1L);

        served.set(0);
        assertThat(UploadService.readLimited(generated(MAX, served))).hasSize(MAX);
    }

    @Test
    @DisplayName("[java] sai định dạng trả 400, kể cả khi body lớn; không đọc body khi loại không phải ảnh")
    void saiDinhDangTra400() {
        var admin = admin();
        var cases = List.of("text/plain", "image/svg+xml", "application/octet-stream", "IMAGE/PNG", "image/pngx", "multipart/form-data; boundary=x");
        for (String type : cases) {
            var res = admin.sendBytes("POST", "/api/admin/uploads", bytes(100), type);
            assertThat(res.status()).as(type).isEqualTo(400);
            assertThat(res.text()).as(type).isEqualTo(BAD_TYPE_BODY);
        }
        var noType = admin.sendBytes("POST", "/api/admin/uploads", bytes(100), null);
        assertThat(noType.status()).isEqualTo(400);
        assertThat(noType.text()).isEqualTo(BAD_TYPE_BODY);
        var empty = admin.sendBytes("POST", "/api/admin/uploads", new byte[0], "image/png");
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.text()).isEqualTo(BAD_TYPE_BODY);
        // Body lớn mà không phải ảnh: Node bỏ qua body và trả 400, không phải 500
        var bigText = admin.sendBytes("POST", "/api/admin/uploads", bytes(4 * 1024 * 1024), "text/plain");
        assertThat(bigText.status()).isEqualTo(400);
        assertThat(bigText.text()).isEqualTo(BAD_TYPE_BODY);
        // Gửi JSON tới đường dẫn tải ảnh cũng là 400 (body {} không phải ảnh)
        admin.post("/api/admin/uploads", body("a", 1)).expectStatus(400);
        assertThat(uploads()).isZero();
    }

    @Test
    @DisplayName("[java] chưa đăng nhập 401, tiếp tân 403, và không đọc body trước khi kiểm quyền")
    void phanQuyenTaiAnh() {
        assertThat(client().sendBytes("POST", "/api/admin/uploads", bytes(10), "image/png").status()).isEqualTo(401);
        assertThat(client().staff().sendBytes("POST", "/api/admin/uploads", bytes(10), "image/png").status()).isEqualTo(403);
        assertThat(client().registerUser().sendBytes("POST", "/api/admin/uploads", bytes(10), "image/png").status()).isEqualTo(403);
        assertThat(uploads()).isZero();
    }

    @Test
    @DisplayName("[java] đọc ảnh: mã không phải số, quá lớn hoặc không tồn tại trả 404 Không tìm thấy ảnh")
    void docAnhKhongCo() {
        for (String id : List.of("abc", "0", "999999", "99999999999999999999", "6ae8a11609f0570702dc6e7f", "-1")) {
            var res = client().get("/api/uploads/" + id);
            assertThat(res.status()).as(id).isEqualTo(404);
            assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("NOT_FOUND");
            assertThat(res.json().get("error").get("message").stringValue()).as(id).isEqualTo("Không tìm thấy ảnh.");
        }
    }

    @Test
    @DisplayName("[java] thumbnail chấp nhận /api/uploads/số, từ chối chữ")
    void thumbnailChapNhanUploadsSo() {
        var admin = admin();
        var up = admin.sendBytes("POST", "/api/admin/uploads", bytes(100), "image/png").expectStatus(201).json();
        Map<String, Object> post = body("title", "Có ảnh", "category", "TIN_TUC", "status", "DRAFT");
        post.put("thumbnailUrl", up.get("url").stringValue());
        var created = admin.post("/api/admin/posts", post).expectStatus(201).json().get("post");
        assertThat(created.get("thumbnailUrl").stringValue()).isEqualTo("/api/uploads/" + up.get("id").stringValue());
        post.put("thumbnailUrl", "/api/uploads/12");
        admin.post("/api/admin/posts", post).expectStatus(201);
        post.put("thumbnailUrl", "/api/uploads/abc");
        admin.post("/api/admin/posts", post).expectStatus(400);
    }
}
