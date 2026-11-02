package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Cổng từ test/posts.test.js (bài viết, ảnh, ghim, API công khai), kèm các test [java] cho điểm Node không có. */
class PostsTest extends IntegrationTest {

    /** Ảnh PNG 1×1 điểm ảnh. */
    static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final Map<String, Object> POST = body("summary", "Mô tả bài", "category", "TIN_TUC", "authorName", "BS",
            "content", "<p>Nội dung</p>", "status", "PUBLISHED");

    /** Node seed bốn trang thông tin cố định; Seeder của Java ở Task 12, nên test tự thêm để kiểm tra việc loại chúng khỏi danh sách. */
    @BeforeEach
    void seedPages() {
        for (String slug : List.of("gioi-thieu", "dich-vu", "huong-dan", "lien-he")) {
            jdbc.update("INSERT INTO posts (title, slug, category, summary, author_id, author_name, content, status, published_at, "
                    + "created_at, updated_at) VALUES (?, ?, 'TRANG', 'Mô tả trang', (SELECT id FROM users WHERE role = 'ADMIN'), "
                    + "'Phòng khám Chúc Từ', '<p>Trang</p>', 'PUBLISHED', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                    "Trang " + slug, slug);
        }
    }

    private TestClient admin() {
        return client().staff("admin@chuctu.vn");
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... overrides) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(base);
        out.putAll(body(overrides));
        return out;
    }

    private static JsonNode create(TestClient admin, Object... overrides) {
        return admin.post("/api/admin/posts", with(POST, overrides)).expectStatus(201).json().get("post");
    }

    private static String id(JsonNode post) {
        return post.get("id").stringValue();
    }

    private static List<String> values(JsonNode items, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : items) out.add(item.get(field).stringValue());
        return out;
    }

    private TestClient.Result upload(TestClient who, byte[] data, String type) {
        return who.sendBytes("POST", "/api/admin/uploads", data, type);
    }

    // ---------- Bài viết và ảnh (UC.5.1 – UC.5.5) ----------

    @Test
    @DisplayName("Admin tải ảnh lên, ai cũng xem được ảnh qua đường dẫn trả về")
    void adminTaiAnhLen() {
        var admin = admin();
        var res = upload(admin, PNG, "image/png").expectStatus(201);
        String url = res.json().get("url").stringValue();
        assertThat(url).matches("^/api/uploads/\\d+$");
        assertThat(res.json().get("id").stringValue()).isEqualTo(url.substring("/api/uploads/".length()));
        var img = client().getBytes(url);
        assertThat(img.status()).isEqualTo(200);
        assertThat(img.headers().firstValue("content-type")).contains("image/png");
        assertThat(img.body()).isEqualTo(PNG);
        upload(admin, "xin chao".getBytes(), "text/plain").expectStatus(400);
    }

    @Test
    @DisplayName("tiếp tân không tải ảnh được")
    void tiepTanKhongTaiAnhDuoc() {
        upload(client().staff(), PNG, "image/png").expectStatus(403);
    }

    @Test
    @DisplayName("ẩn bài viết thì bài rời website, công khai lại thì hiện trở lại")
    void anBaiVietThiBaiRoiWebsite() {
        var admin = admin();
        var up = upload(admin, PNG, "image/png").expectStatus(201);
        var created = admin.post("/api/admin/posts", body("title", "Lịch khám dịp lễ", "summary", "Giờ khám các ngày lễ.",
                "category", "TIN_TUC", "authorName", "Phòng khám Chúc Từ", "thumbnailUrl", up.json().get("url").stringValue(),
                "content", "<p>Nội dung</p>", "status", "PUBLISHED")).expectStatus(201);
        String id = id(created.json().get("post"));
        client().get("/api/posts/" + id).expectStatus(200);

        var hidden = admin.put("/api/admin/posts/" + id, body("status", "HIDDEN")).expectStatus(200);
        assertThat(hidden.json().get("post").get("status").stringValue()).isEqualTo("HIDDEN");
        assertThat(hidden.json().get("post").get("publishedAt").isNull()).isFalse();
        client().get("/api/posts/" + id).expectStatus(404);
        var list = admin.get("/api/admin/posts?status=HIDDEN").expectStatus(200).json();
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("statusCounts").get("HIDDEN").intValue()).isEqualTo(1);

        admin.put("/api/admin/posts/" + id, body("status", "PUBLISHED")).expectStatus(200);
        client().get("/api/posts/" + id).expectStatus(200);
    }

    @Test
    @DisplayName("bản nháp giữ ngày đăng đã chọn để dùng khi công khai")
    void banNhapGiuNgayDang() {
        var admin = admin();
        String publishedAt = "2026-11-02T01:00:00.000Z";
        var created = admin.post("/api/admin/posts", body("title", "Bài nháp", "category", "TIN_TUC", "publishedAt", publishedAt,
                "status", "DRAFT")).expectStatus(201);
        assertThat(created.json().get("post").get("publishedAt").stringValue()).isEqualTo(publishedAt);
        var done = admin.put("/api/admin/posts/" + id(created.json().get("post")), body("content", "Nội dung", "summary", "Mô tả bài",
                "authorName", "BS", "status", "PUBLISHED")).expectStatus(200);
        assertThat(done.json().get("post").get("publishedAt").stringValue()).isEqualTo(publishedAt);
    }

    @Test
    @DisplayName("công khai bài phải có mô tả; bản nháp thì chưa cần")
    void congKhaiBaiPhaiCoMoTa() {
        var admin = admin();
        var draft = admin.post("/api/admin/posts", body("title", "Chưa có mô tả", "category", "TIN_TUC", "status", "DRAFT")).expectStatus(201);
        String id = id(draft.json().get("post"));
        var res = admin.put("/api/admin/posts/" + id, body("content", "<p>Nội dung</p>", "authorName", "BS", "status", "PUBLISHED"))
                .expectStatus(400);
        assertThat(res.json().get("error").get("fields").get("summary").stringValue()).isEqualTo("Bài viết cần có mô tả.");
        admin.put("/api/admin/posts/" + id, body("summary", "   ", "status", "PUBLISHED")).expectStatus(400);
    }

    // ---------- Danh sách bài viết của quản trị ----------

    @Test
    @DisplayName("không hiện trang thông tin cố định, chỉ 4 danh mục bài viết như bên website")
    void khongHienTrangThongTinCoDinh() {
        var admin = admin();
        create(admin, "title", "Một bài");
        var list = admin.get("/api/admin/posts?limit=50").expectStatus(200).json();
        for (JsonNode p : list.get("items")) {
            assertThat(p.get("category").stringValue()).isNotEqualTo("TRANG");
            assertThat(p.get("slug").isNull()).isTrue();
        }
        JsonNode counts = list.get("statusCounts");
        assertThat(counts.get("PUBLISHED").intValue() + counts.get("DRAFT").intValue() + counts.get("HIDDEN").intValue())
                .isEqualTo(list.get("total").intValue()).isEqualTo(1);
        // Lọc theo TRANG cũng không lộ ra trang thông tin
        var pages = admin.get("/api/admin/posts?category=TRANG").expectStatus(200).json();
        for (JsonNode p : pages.get("items")) assertThat(p.get("category").stringValue()).isNotEqualTo("TRANG");
        // Trang thông tin vẫn còn cho trang Giới thiệu… dùng
        client().get("/api/posts/gioi-thieu").expectStatus(200);
    }

    // ---------- Thứ tự và phân trang ở trang quản trị ----------

    private List<String> titles(TestClient admin, String qs) {
        return values(admin.get("/api/admin/posts?limit=10" + qs).expectStatus(200).json().get("items"), "title");
    }

    @Test
    @DisplayName("mỗi trang tối đa 10 bài, bài mới thêm đứng đầu trang 1")
    void moiTrangToiDa10Bai() {
        var admin = admin();
        List<String> added = new ArrayList<>();
        for (int i = 1; i <= 12; i++) added.add(create(admin, "title", "Bài " + i).get("title").stringValue());

        var first = titles(admin, "&page=1");
        var second = titles(admin, "&page=2");
        assertThat(first).hasSize(10);
        assertThat(first.get(0)).isEqualTo("Bài 12");
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        assertThat(all).containsExactlyInAnyOrderElementsOf(added);

        create(admin, "title", "Bài vừa thêm", "status", "DRAFT");
        assertThat(titles(admin, "&page=1").get(0)).isEqualTo("Bài vừa thêm");
    }

    @Test
    @DisplayName("sửa, ẩn hoặc ghim bài thì bài không đổi chỗ")
    void suaAnHoacGhimThiBaiKhongDoiCho() {
        var admin = admin();
        var old = create(admin, "title", "Bài cũ");
        create(admin, "title", "Bài mới");

        admin.put("/api/admin/posts/" + id(old), body("title", "Bài cũ đã sửa")).expectStatus(200);
        admin.put("/api/admin/posts/" + id(old), body("status", "HIDDEN")).expectStatus(200);
        admin.put("/api/admin/posts/" + id(old), body("status", "PUBLISHED")).expectStatus(200);
        admin.post("/api/admin/posts/" + id(old) + "/pin", body()).expectStatus(200);
        assertThat(titles(admin, "&page=1").subList(0, 2)).containsExactly("Bài mới", "Bài cũ đã sửa");
    }

    // ---------- Bài viết công khai (BR.08, UC.4.1 – UC.4.3) ----------

    private JsonNode publicList(String qs) {
        return client().get("/api/posts" + qs).expectStatus(200).json();
    }

    @Test
    @DisplayName("chỉ hiện bài đã công khai, không có bản nháp, bài ẩn và trang cố định")
    void chiHienBaiDaCongKhai() {
        var admin = admin();
        var shown = create(admin, "title", "Bài công khai");
        var draft = create(admin, "title", "Bản nháp", "status", "DRAFT");
        var hidden = create(admin, "title", "Bài ẩn", "status", "HIDDEN");

        var list = publicList("");
        assertThat(values(list.get("items"), "id")).containsExactly(id(shown));
        assertThat(list.get("total").intValue()).isEqualTo(1);
        assertThat(list.get("items").get(0).has("content")).isFalse();
        client().get("/api/posts/" + id(draft)).expectStatus(404);
        client().get("/api/posts/" + id(hidden)).expectStatus(404);
    }

    @Test
    @DisplayName("lọc theo danh mục, tìm theo tiêu đề, bài mới đăng lên trước")
    void locTheoDanhMucTimTheoTieuDe() {
        var admin = admin();
        var older = create(admin, "title", "Khám miễn phí tháng 10", "publishedAt", "2026-10-01T01:00:00.000Z");
        var newer = create(admin, "title", "Khám miễn phí tháng 11", "publishedAt", "2026-11-01T01:00:00.000Z");
        var guide = create(admin, "title", "Sắc thuốc đúng cách", "category", "HUONG_DAN");

        assertThat(values(publicList("?category=HUONG_DAN").get("items"), "id")).containsExactly(id(guide));
        assertThat(values(publicList("?q=" + java.net.URLEncoder.encode("miễn phí", java.nio.charset.StandardCharsets.UTF_8)).get("items"), "id"))
                .containsExactly(id(newer), id(older));
        // Ký tự đặc biệt trong ô tìm kiếm không được làm hỏng truy vấn
        assertThat(publicList("?q=" + java.net.URLEncoder.encode("(*", java.nio.charset.StandardCharsets.UTF_8)).get("items")).isEmpty();
    }

    // ---------- Ghim bài và bài nổi bật ----------

    private JsonNode featuredList(String qs) {
        return client().get("/api/posts?featured=1" + qs).expectStatus(200).json();
    }

    private List<JsonNode> seed(TestClient admin, int count) {
        List<JsonNode> posts = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            posts.add(create(admin, "title", "Bài " + i, "publishedAt", java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(86400L * (i - 1)).toString()));
        }
        return posts;
    }

    @Test
    @DisplayName("bài ghim đứng yên ở mọi trang, các trang chỉ chia 9 bài còn lại")
    void baiGhimDungYenOMoiTrang() {
        var admin = admin();
        var posts = seed(admin, 12);
        admin.post("/api/admin/posts/" + id(posts.get(0)) + "/pin", body()).expectStatus(200);

        var first = featuredList("&page=1&limit=9");
        var second = featuredList("&page=2&limit=9");
        assertThat(first.get("featured").get("id").stringValue()).isEqualTo(id(posts.get(0)));
        assertThat(second.get("featured").get("id").stringValue()).isEqualTo(id(posts.get(0)));
        assertThat(first.get("total").intValue()).isEqualTo(12);
        assertThat(first.get("items")).hasSize(9);
        assertThat(second.get("items")).hasSize(2);
        List<String> shown = new ArrayList<>(values(first.get("items"), "id"));
        shown.addAll(values(second.get("items"), "id"));
        assertThat(shown).doesNotHaveDuplicates().hasSize(11).doesNotContain(id(posts.get(0)));
    }

    @Test
    @DisplayName("chưa ghim thì bài mới nhất làm bài nổi bật")
    void chuaGhimThiBaiMoiNhatNoiBat() {
        var posts = seed(admin(), 3);
        var list = featuredList("");
        assertThat(list.get("featured").get("id").stringValue()).isEqualTo(id(posts.get(2)));
        assertThat(values(list.get("items"), "id")).containsExactly(id(posts.get(1)), id(posts.get(0)));
    }

    @Test
    @DisplayName("bỏ ghim thì bài mới nhất trở lại, kể cả khi có bài cũ chưa từng ghim")
    void boGhimThiBaiMoiNhatTroLai() {
        var admin = admin();
        var posts = seed(admin, 3);
        admin.post("/api/admin/posts/" + id(posts.get(0)) + "/pin", body()).expectStatus(200);
        var post = admin.post("/api/admin/posts/" + id(posts.get(0)) + "/unpin", body()).expectStatus(200).json().get("post");
        assertThat(post.get("pinned").booleanValue()).isFalse();
        assertThat(featuredList("").get("featured").get("id").stringValue()).isEqualTo(id(posts.get(2)));
    }

    @Test
    @DisplayName("mỗi lần chỉ ghim một bài: ghim bài mới thì bài cũ tự bỏ ghim")
    void moiLanChiGhimMotBai() {
        var admin = admin();
        var posts = seed(admin, 2);
        String a = id(posts.get(0));
        String b = id(posts.get(1));
        admin.post("/api/admin/posts/" + a + "/pin", body()).expectStatus(200);
        admin.post("/api/admin/posts/" + a + "/pin", body()).expectStatus(200);
        admin.post("/api/admin/posts/" + b + "/pin", body()).expectStatus(200);

        var items = admin.get("/api/admin/posts?limit=50").expectStatus(200).json().get("items");
        List<String> pinned = new ArrayList<>();
        for (JsonNode p : items) if (p.get("pinned").booleanValue()) pinned.add(id(p));
        assertThat(pinned).containsExactly(b);
    }

    @Test
    @DisplayName("lọc chuyên mục khác thì bài nổi bật là bài mới nhất của chuyên mục đó")
    void locChuyenMucKhac() {
        var admin = admin();
        var pinned = seed(admin, 2).get(0);
        var guide = create(admin, "title", "Hướng dẫn", "category", "HUONG_DAN");
        admin.post("/api/admin/posts/" + id(pinned) + "/pin", body()).expectStatus(200);

        assertThat(featuredList("&category=HUONG_DAN").get("featured").get("id").stringValue()).isEqualTo(id(guide));
        assertThat(featuredList("&category=TIN_TUC").get("featured").get("id").stringValue()).isEqualTo(id(pinned));
        assertThat(featuredList("&category=SU_KIEN").get("featured").isNull()).isTrue();
    }

    @Test
    @DisplayName("ghim không đổi giờ cập nhật")
    void ghimKhongDoiGioCapNhat() {
        var admin = admin();
        var post = seed(admin, 1).get(0);
        var pinned = admin.post("/api/admin/posts/" + id(post) + "/pin", body()).expectStatus(200).json().get("post");
        assertThat(pinned.get("pinned").booleanValue()).isTrue();
        assertThat(pinned.get("updatedAt").stringValue()).isEqualTo(post.get("updatedAt").stringValue());
    }

    @Test
    @DisplayName("ẩn bài đang ghim thì mất ghim, công khai lại không tự ghim")
    void anBaiDangGhim() {
        var admin = admin();
        var posts = seed(admin, 2);
        String a = id(posts.get(0));
        admin.post("/api/admin/posts/" + a + "/pin", body()).expectStatus(200);

        var hidden = admin.put("/api/admin/posts/" + a, body("status", "HIDDEN")).expectStatus(200).json().get("post");
        assertThat(hidden.get("pinned").booleanValue()).isFalse();
        admin.put("/api/admin/posts/" + a, body("status", "PUBLISHED")).expectStatus(200);
        assertThat(featuredList("").get("featured").get("id").stringValue()).isEqualTo(id(posts.get(1)));
    }

    @Test
    @DisplayName("chỉ ghim được bài đã công khai, không ghim trang cố định, tiếp tân không ghim được")
    void chiGhimDuocBaiDaCongKhai() {
        var admin = admin();
        var staff = client().staff();
        var draft = create(admin, "title", "Bản nháp", "status", "DRAFT");
        var page = client().get("/api/posts/gioi-thieu").expectStatus(200).json().get("post");
        var post = seed(admin, 1).get(0);

        var res = admin.post("/api/admin/posts/" + id(draft) + "/pin", body()).expectStatus(400);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("NOT_PINNABLE");
        admin.post("/api/admin/posts/" + id(page) + "/pin", body()).expectStatus(400);
        staff.post("/api/admin/posts/" + id(post) + "/pin", body()).expectStatus(403);
        admin.post("/api/admin/posts/999999/pin", body()).expectStatus(404);
    }

    @Test
    @DisplayName("danh sách thường không tách bài nổi bật (trang chủ dùng)")
    void danhSachThuongKhongTachBaiNoiBat() {
        var admin = admin();
        var posts = seed(admin, 3);
        admin.post("/api/admin/posts/" + id(posts.get(0)) + "/pin", body()).expectStatus(200);
        var list = client().get("/api/posts?limit=3").expectStatus(200).json();
        assertThat(list.get("featured").isNull()).isTrue();
        assertThat(values(list.get("items"), "id")).containsExactly(id(posts.get(2)), id(posts.get(1)), id(posts.get(0)));
    }

    // ---------- Xoá bài viết (UC.5.5) ----------

    @Test
    @DisplayName("xoá xong bài rời website và không khôi phục được")
    void xoaXongBaiRoiWebsite() {
        var admin = admin();
        var post = create(admin, "title", "Bài sẽ xoá");
        var res = admin.delete("/api/admin/posts/" + id(post)).expectStatus(200);
        assertThat(res.json().get("deleted").booleanValue()).isTrue();
        client().get("/api/posts/" + id(post)).expectStatus(404);
        admin.get("/api/admin/posts/" + id(post)).expectStatus(404);
        admin.delete("/api/admin/posts/" + id(post)).expectStatus(404);
    }

    @Test
    @DisplayName("không xoá được trang thông tin cố định")
    void khongXoaDuocTrangCoDinh() {
        var admin = admin();
        var page = client().get("/api/posts/gioi-thieu").expectStatus(200).json().get("post");
        admin.delete("/api/admin/posts/" + id(page)).expectStatus(400);
        client().get("/api/posts/gioi-thieu").expectStatus(200);
    }

    @Test
    @DisplayName("tiếp tân không xoá và không sửa được bài")
    void tiepTanKhongXoaVaKhongSua() {
        var admin = admin();
        var staff = client().staff();
        var post = create(admin, "title", "Bài của Admin");
        staff.delete("/api/admin/posts/" + id(post)).expectStatus(403);
        staff.put("/api/admin/posts/" + id(post), body("status", "HIDDEN")).expectStatus(403);
    }

    // ---------- [java] Điểm Node không có ----------

    @Test
    @DisplayName("[java] hình dạng bài viết giống postView của Node")
    void hinhDangBaiViet() {
        var post = create(admin(), "title", "Bài mẫu", "thumbnailUrl", "https://example.com/a.png");
        assertThat(post.propertyNames()).containsExactly("id", "title", "slug", "category", "categoryLabel", "summary",
                "thumbnailUrl", "authorName", "status", "pinned", "publishedAt", "createdAt", "updatedAt", "content");
        assertThat(post.get("slug").isNull()).isTrue();
        assertThat(post.get("categoryLabel").stringValue()).isEqualTo("Tin tức");
        assertThat(post.get("thumbnailUrl").stringValue()).isEqualTo("https://example.com/a.png");
        assertThat(post.get("pinned").booleanValue()).isFalse();
        assertThat(post.get("createdAt").stringValue()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");
        assertThat(post.get("publishedAt").stringValue()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");
        var summary = client().get("/api/posts").expectStatus(200).json();
        assertThat(summary.propertyNames()).containsExactly("featured", "items", "total", "page", "limit");
        assertThat(summary.get("limit").intValue()).isEqualTo(9);
        assertThat(summary.get("items").get(0).has("content")).isFalse();
    }

    @Test
    @DisplayName("[java] mã bài toàn chữ số là ID, mã quá lớn là slug nên 404 chứ không phải 500")
    void maBaiQuaLonTra404() {
        var post = create(admin(), "title", "Bài mẫu");
        client().get("/api/posts/" + id(post)).expectStatus(200);
        for (String bad : List.of("99999999999999999999", "9223372036854775808", "123456789012345678901234", "khong-co-bai-nay", "0")) {
            var res = client().get("/api/posts/" + bad).expectStatus(404);
            assertThat(res.json().get("error").get("message").stringValue()).isEqualTo("Bài viết không tồn tại hoặc chưa được đăng.");
        }
        // Quản trị: mã không phải số là CastError của Mongoose (404 chung), mã số không có bài là 404 riêng
        var admin = admin();
        assertThat(admin.get("/api/admin/posts/khong-phai-so").expectStatus(404).json().get("error").get("message").stringValue())
                .isEqualTo("Không tìm thấy dữ liệu.");
        assertThat(admin.get("/api/admin/posts/99999999999999999999").expectStatus(404).json().get("error").get("message").stringValue())
                .isEqualTo("Không tìm thấy dữ liệu.");
        assertThat(admin.get("/api/admin/posts/123456").expectStatus(404).json().get("error").get("message").stringValue())
                .isEqualTo("Không tìm thấy bài viết.");
    }

    @Test
    @DisplayName("[java] thumbnailUrl nhận /api/uploads/ cộng số, không nhận chữ; URL đầy đủ và rỗng vẫn như Node")
    void thumbnailChapNhanUploadsSo() {
        var admin = admin();
        assertThat(create(admin, "thumbnailUrl", "/api/uploads/12", "title", "A").get("thumbnailUrl").stringValue()).isEqualTo("/api/uploads/12");
        assertThat(create(admin, "thumbnailUrl", "  https://example.com/a.png  ", "title", "B").get("thumbnailUrl").stringValue())
                .isEqualTo("https://example.com/a.png");
        assertThat(create(admin, "thumbnailUrl", "", "title", "C").get("thumbnailUrl").isNull()).isTrue();
        assertThat(create(admin, "thumbnailUrl", null, "title", "D").get("thumbnailUrl").isNull()).isTrue();
        for (String bad : List.of("/api/uploads/abc", "/api/uploads/", "/api/uploads/12/x", " /api/uploads/12", "/api/uploads/6ae8a11609f0570702dc6e7f", "anh.png", "   ")) {
            var res = admin.post("/api/admin/posts", with(POST, "title", "X", "thumbnailUrl", bad)).expectStatus(400);
            assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("VALIDATION");
            assertThat(res.json().get("error").get("fields").get("thumbnailUrl").stringValue()).isEqualTo("Đường dẫn ảnh chưa đúng.");
        }
        var number = admin.post("/api/admin/posts", with(POST, "title", "X", "thumbnailUrl", 5)).expectStatus(400);
        assertThat(number.json().get("error").get("fields").get("thumbnailUrl").stringValue()).isEqualTo("Invalid input");
    }

    @Test
    @DisplayName("[java] kiểm tra dữ liệu bài viết: tiêu đề, chuyên mục, độ dài, ngày đăng")
    void kiemTraDuLieuBaiViet() {
        var admin = admin();
        var fields = admin.post("/api/admin/posts", body()).expectStatus(400).json().get("error");
        assertThat(fields.get("code").stringValue()).isEqualTo("VALIDATION");
        assertThat(fields.get("fields").get("category").stringValue()).isEqualTo("Vui lòng chọn chuyên mục.");
        var noTitle = admin.post("/api/admin/posts", body("category", "TIN_TUC", "title", "   ")).expectStatus(400).json().get("error");
        assertThat(noTitle.get("fields").get("title").stringValue()).isEqualTo("Bài viết cần có tiêu đề.");
        var many = admin.post("/api/admin/posts", body("title", "t".repeat(201), "category", "SAI", "summary", "s".repeat(301),
                "publishedAt", "2026-02-30T00:00:00Z", "status", "KHAC")).expectStatus(400).json().get("error").get("fields");
        assertThat(many.get("title").stringValue()).isEqualTo("Tiêu đề tối đa 200 ký tự.");
        assertThat(many.get("category").stringValue()).isEqualTo("Vui lòng chọn chuyên mục.");
        assertThat(many.get("summary").stringValue()).isEqualTo("Mô tả tối đa 300 ký tự.");
        assertThat(many.get("publishedAt").stringValue()).isEqualTo("Invalid datetime");
        assertThat(many.get("status").stringValue()).startsWith("Invalid enum value. Expected 'DRAFT' | 'PUBLISHED' | 'HIDDEN'");
        // Ngày đăng có múi giờ được đổi sang UTC
        var offset = create(admin, "title", "Có múi giờ", "publishedAt", "2026-11-02T08:30:15.123456+07:00");
        assertThat(offset.get("publishedAt").stringValue()).isEqualTo("2026-11-02T01:30:15.123Z");
        // Công khai thiếu thông tin: đủ các ô thiếu
        var draft = admin.post("/api/admin/posts", body("title", "Nháp", "category", "TIN_TUC")).expectStatus(201).json().get("post");
        var missing = admin.put("/api/admin/posts/" + id(draft), body("status", "PUBLISHED")).expectStatus(400).json().get("error");
        assertThat(missing.get("code").stringValue()).isEqualTo("NOT_PUBLISHABLE");
        assertThat(missing.get("message").stringValue()).isEqualTo("Bài viết còn thiếu thông tin nên chưa đăng được.");
        assertThat(missing.get("fields").propertyNames()).containsExactly("summary", "content");
    }

    @Test
    @DisplayName("[java] tác giả mặc định là tên Admin, bài tạo ra giữ nguyên nội dung có dấu và ký tự đặc biệt")
    void tacGiaMacDinh() {
        var admin = admin();
        var post = admin.post("/api/admin/posts", body("title", "  Tiêu đề 🌿 \"trích\" & <b>  ", "category", "SU_KIEN",
                "summary", "Mô tả", "content", "<p>Chào \\ 'bạn' 😀</p>", "status", "PUBLISHED")).expectStatus(201).json().get("post");
        assertThat(post.get("authorName").stringValue()).isEqualTo(config.seedAdmin.fullName());
        assertThat(post.get("title").stringValue()).isEqualTo("Tiêu đề 🌿 \"trích\" & <b>");
        assertThat(post.get("content").stringValue()).isEqualTo("<p>Chào \\ 'bạn' 😀</p>");
        assertThat(admin.get("/api/admin/posts/" + id(post)).expectStatus(200).json().get("post").get("content").stringValue())
                .isEqualTo("<p>Chào \\ 'bạn' 😀</p>");
    }

    @Test
    @DisplayName("[java] tìm theo tiêu đề không phân biệt hoa thường, chứa % và _ được hiểu đúng nghĩa đen")
    void timTheoTieuDe() {
        var admin = admin();
        create(admin, "title", "Khám 100% miễn phí");
        create(admin, "title", "Khám 1000 người");
        create(admin, "title", "Tên_gạch dưới");
        create(admin, "title", "Tên gạch dưới");
        assertThat(publicList("?q=MIỄN").get("total").intValue()).isEqualTo(1);
        assertThat(publicList("?q=" + java.net.URLEncoder.encode("100%", java.nio.charset.StandardCharsets.UTF_8)).get("total").intValue()).isEqualTo(1);
        assertThat(publicList("?q=" + java.net.URLEncoder.encode("n_g", java.nio.charset.StandardCharsets.UTF_8)).get("total").intValue()).isEqualTo(1);
        assertThat(admin.get("/api/admin/posts?q=" + java.net.URLEncoder.encode("\\", java.nio.charset.StandardCharsets.UTF_8)).expectStatus(200)
                .json().get("total").intValue()).isZero();
        // Khác dấu thì khác chữ, như regex /i của Node
        assertThat(publicList("?q=kham").get("total").intValue()).isZero();
    }

    @Test
    @DisplayName("[java] trang thông tin cố định: sửa được, ép về TRANG, ghim thì báo lỗi, công khai theo slug")
    void trangThongTinCoDinh() {
        var admin = admin();
        var page = client().get("/api/posts/dich-vu").expectStatus(200).json().get("post");
        assertThat(page.get("slug").stringValue()).isEqualTo("dich-vu");
        var edited = admin.put("/api/admin/posts/" + id(page), body("category", "TIN_TUC", "title", "Dịch vụ mới")).expectStatus(200)
                .json().get("post");
        assertThat(edited.get("category").stringValue()).isEqualTo("TRANG");
        assertThat(edited.get("title").stringValue()).isEqualTo("Dịch vụ mới");
        // Lọc công khai theo TRANG vẫn thấy trang (Node dùng POST_CATEGORIES[category])
        assertThat(publicList("?category=TRANG").get("total").intValue()).isEqualTo(4);
        assertThat(publicList("").get("total").intValue()).isZero();
        assertThat(admin.get("/api/admin/posts?category=TRANG").expectStatus(200).json().get("total").intValue()).isZero();
    }

    @Test
    @DisplayName("[java] cập nhật bài chỉ đổi giờ cập nhật khi có thay đổi thật")
    void capNhatKhongDoiKhiKhongThayDoi() throws Exception {
        var admin = admin();
        var post = create(admin, "title", "Bài mẫu");
        Thread.sleep(5);
        var same = admin.put("/api/admin/posts/" + id(post), body("title", "Bài mẫu")).expectStatus(200).json().get("post");
        assertThat(same.get("updatedAt").stringValue()).isEqualTo(post.get("updatedAt").stringValue());
        var changed = admin.put("/api/admin/posts/" + id(post), body("title", "Bài mẫu 2")).expectStatus(200).json().get("post");
        assertThat(changed.get("updatedAt").stringValue()).isNotEqualTo(post.get("updatedAt").stringValue());
    }

    @Test
    @DisplayName("[java] ghim đồng thời hai bài: luôn chỉ còn một bài ghim, không lỗi")
    void ghimDongThoi() throws Exception {
        var admin = admin();
        var posts = seed(admin, 2);
        String a = id(posts.get(0));
        String b = id(posts.get(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                jdbc.update("UPDATE posts SET pinned = NULL");
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (String target : List.of(a, b)) {
                    var agent = admin();
                    results.add(pool.submit(() -> {
                        go.await();
                        return agent.post("/api/admin/posts/" + target + "/pin", body()).status();
                    }));
                }
                go.countDown();
                for (Future<Integer> result : results) assertThat(result.get()).isEqualTo(200);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE pinned = 1", Integer.class)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("[java] ảnh bìa là URL dài 2000 ký tự lưu và đọc lại đủ, không lỗi 500")
    void anhBiaUrlDai() {
        var admin = admin();
        String url = "https://example.com/" + "a".repeat(2000 - "https://example.com/".length());
        assertThat(url).hasSize(2000);
        var post = create(admin, "title", "Ảnh bìa dài", "thumbnailUrl", url);
        assertThat(post.get("thumbnailUrl").stringValue()).isEqualTo(url);
        assertThat(admin.get("/api/admin/posts/" + id(post)).expectStatus(200).json().get("post").get("thumbnailUrl").stringValue()).isEqualTo(url);
        var updated = admin.put("/api/admin/posts/" + id(post), body("thumbnailUrl", url + "?v=2")).expectStatus(200).json().get("post");
        assertThat(updated.get("thumbnailUrl").stringValue()).isEqualTo(url + "?v=2");
    }

    @Test
    @DisplayName("[java] cột email rộng 700 ký tự để nhận email dài hợp lệ")
    void cotEmailRong() {
        for (String table : List.of("users", "verification_codes")) {
            assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns "
                    + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = 'email'", Integer.class, table)).isEqualTo(700);
        }
        String email = "a".repeat(600) + "@example.com";
        jdbc.update("INSERT INTO users (phone, email, full_name, role, created_at, updated_at) VALUES ('0900009999', ?, 'Dài', 'USER', "
                + "UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", email);
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE phone = '0900009999'", String.class)).isEqualTo(email);
    }
}
