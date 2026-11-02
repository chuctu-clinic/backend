package vn.chuctu.clinic.seed;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;
import vn.chuctu.clinic.auth.AuthService;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.common.AppProperties.SeedAccount;
import vn.chuctu.clinic.common.Constants;
import vn.chuctu.clinic.common.Rules;
import vn.chuctu.clinic.common.Tx;
import vn.chuctu.clinic.patient.PatientService;
import vn.chuctu.clinic.patient.PatientService.PatientInput;

/**
 * Nạp dữ liệu ban đầu (seed.js): tài khoản Admin và Tiếp tân theo SEED_*, 4 trang thông tin và (chế độ demo) dữ liệu mẫu.
 * Chỉ tạo cái còn thiếu nên chạy lại bao nhiêu lần cũng không trùng.
 */
@Component
public class Seeder {

    private static final Logger log = LoggerFactory.getLogger(Seeder.class);

    /** Kết quả seed; receptionist là null khi production mà không đặt SEED_RECEPTIONIST_PASSWORD. */
    public record SeedResult(User admin, User receptionist, long patients) {}

    private record Page(String slug, String title, String summary, String content) {}

    private record DemoPost(String title, String category, String summary, String content, int daysAgo) {}

    private static final List<Page> PAGES = List.of(
            new Page("gioi-thieu", "Về Phòng khám Chúc Từ",
                    "Phòng Chẩn Trị Y Học Cổ Truyền Từ Thiện Chúc Từ — khám bệnh, trị liệu và bốc thuốc hoàn toàn miễn phí cho người già neo đơn, người lao động nghèo và bà con có hoàn cảnh khó khăn. Phòng khám mở cửa tất cả các ngày trong tuần, kể cả Chủ Nhật, từ 8:00 đến 16:30.",
                    """
                    Phòng Chẩn Trị Y Học Cổ Truyền Từ Thiện Chúc Từ là cơ sở khám chữa bệnh bằng y học cổ truyền **miễn phí** cho người có hoàn cảnh khó khăn.

                    Phòng khám chính thức hoạt động từ ngày **08/11/2025** (tức ngày 19/09 Âm lịch).

                    ## Chúng tôi làm gì

                    Phòng khám chuyên điều trị và phục hồi chức năng bằng phương pháp y học cổ truyền: châm cứu, bấm huyệt, xoa bóp và kê toa thuốc y học cổ truyền.

                    Bà con được thăm khám, điều trị các bệnh về cơ xương khớp, đau nhức, tai biến, mất ngủ, đau đầu… Toàn bộ quá trình từ khám, trị liệu đến bốc thuốc đều hoàn toàn miễn phí, nhờ tấm lòng y đức của các y bác sĩ và sự đóng góp của các Mạnh Thường Quân.

                    ## Người phụ trách

                    Sư cô Chúc Từ — điện thoại 0397 116 666 hoặc 0835 555 115."""),
            new Page("dich-vu", "Dịch vụ khám và điều trị",
                    "Phòng khám chuyên điều trị và phục hồi chức năng bằng y học cổ truyền: bệnh cơ xương khớp, đau nhức, tai biến, mất ngủ, đau đầu. Bác sĩ xem mạch và chẩn đoán trước, rồi mới chọn phương pháp. Tất cả đều không thu phí.",
                    """
                    ## Châm cứu
                    Giảm đau vai gáy, đau lưng, tê bì chân tay và di chứng sau tai biến.

                    ## Vật lý trị liệu — phục hồi chức năng
                    Tập phục hồi vận động sau chấn thương, thoái hoá khớp và cứng khớp.

                    ## Xoa bóp — bấm huyệt
                    Thư giãn cơ, giảm mỏi và hỗ trợ giấc ngủ, thường kết hợp cùng châm cứu.

                    ## Bốc thuốc — thuốc Nam, thuốc Bắc
                    Bắt mạch, kê thang thuốc Nam – thuốc Bắc hoặc thuốc y học cổ truyền bào chế sẵn. Bà con nhận thuốc tại chỗ, không mất tiền."""),
            new Page("huong-dan", "Hướng dẫn đi khám",
                    "Cô chú có thể đăng ký trước trên mạng cho đỡ phải chờ, hoặc cứ tới thẳng phòng khám trong giờ làm việc. Cách nào cũng được.",
                    """
                    ## Đặt lịch trên website
                    1. **Đăng nhập** bằng số điện thoại. Chưa có tài khoản thì tạo một lần, lần sau khỏi nhập lại.
                    2. **Đặt lịch**: chọn nội dung khám, ngày, khung giờ và người đi khám rồi bấm đặt lịch.
                    3. **Nhận phiếu khám** ngay, có mã phiếu khám và giờ khám dự kiến, xem trong mục Phiếu khám bệnh.
                    4. **Đến khám**: đưa phiếu khám cho tiếp tân để check-in. Không đến được thì huỷ phiếu ngay trên trang.

                    Giờ trên phiếu là giờ dự kiến. Thứ tự vào khám do hàng đợi tại phòng khám sắp xếp: ai có phiếu đặt trước và đến đúng khung giờ được xếp lên trước.

                    ## Quy trình tại phòng khám
                    1. **Đăng ký**: tình nguyện viên hoặc nhân viên tiếp đón, ghi nhận thông tin.
                    2. **Thăm khám**: bác sĩ/y sĩ y học cổ truyền xem mạch, chẩn đoán và đưa ra phác đồ điều trị.
                    3. **Trị liệu tại chỗ** (nếu có): châm cứu, bấm huyệt hoặc xoa bóp.
                    4. **Nhận thuốc** (nếu có chỉ định) rồi ra về.

                    ## Lưu ý khi đến khám
                    - **Mang theo hồ sơ bệnh án cũ** nếu có: phim X-quang, MRI, đơn thuốc đang dùng.
                    - **Mặc quần áo rộng rãi**, ống rộng hoặc cài nút, để thuận tiện châm cứu, xoa bóp.
                    - **Không để bụng quá đói** khi đi châm cứu vì dễ bị choáng (vựng châm); cũng không ăn quá no ngay trước giờ khám.
                    - Buổi sáng thường rất đông, nên đến sớm đầu giờ hoặc gọi trước cho phòng khám."""),
            new Page("lien-he", "Liên hệ phòng khám",
                    "Gọi điện là cách nhanh nhất — có người trực nghe máy trong giờ làm việc. Cô chú cũng có thể tới thẳng phòng khám, không cần hẹn trước.",
                    """
                    **Địa chỉ:** 57/8-10 Điện Biên Phủ, Phường 25 (trước đây thuộc P.15), Quận Bình Thạnh, TP. Hồ Chí Minh.

                    **Điện thoại:** 0397 116 666 hoặc 0835 555 115 (Sư cô Chúc Từ).

                    **Giờ nhận bệnh:** 8:00 – 16:30 tất cả các ngày trong tuần, kể cả Chủ Nhật.

                    Lịch làm việc của các bác sĩ có thể thay đổi, có ngày rất đông bệnh nhân. Bà con nên gọi trước để hỏi khung giờ nhận bệnh trong ngày."""));

    private static final List<DemoPost> DEMO_POSTS = List.of(
            new DemoPost("Lịch khám trong dịp lễ sắp tới", "TIN_TUC",
                    "Phòng khám nghỉ hai ngày lễ, các ngày còn lại vẫn nhận bệnh bình thường 8:00 – 16:30.",
                    """
                    Phòng khám sẽ thông báo cụ thể hai ngày nghỉ lễ trên trang này.

                    Các ngày còn lại phòng khám vẫn nhận bệnh bình thường từ **8:00 đến 16:30**. Cô chú đã có phiếu khám vào ngày nghỉ sẽ được phòng khám gọi điện hẹn lại.""",
                    1),
            new DemoPost("Cần chuẩn bị gì trước buổi châm cứu đầu tiên", "HUONG_DAN",
                    "Cô chú nên ăn nhẹ, mặc đồ rộng rãi và mang theo toa thuốc đang uống khi tới khám.",
                    """
                    Buổi châm cứu đầu tiên sẽ nhẹ nhàng hơn nếu cô chú chuẩn bị trước vài điều:

                    - **Ăn nhẹ trước khi đến**, không để bụng đói.
                    - **Mặc đồ rộng rãi**, dễ vén tay áo, ống quần.
                    - **Mang theo toa thuốc** đang uống và kết quả khám cũ nếu có.

                    Nếu thấy chóng mặt, cồn cào trong lúc châm, cô chú báo ngay cho bác sĩ.""",
                    3),
            new DemoPost("Giờ nhận bệnh và lưu ý khi đến khám", "TIN_TUC",
                    "Phòng khám nhận bệnh 8:00 – 16:30 mỗi ngày, kể cả Chủ Nhật. Buổi sáng thường rất đông.",
                    """
                    Phòng khám nhận bệnh từ **8:00 đến 16:30** tất cả các ngày trong tuần, kể cả Chủ Nhật.

                    Buổi sáng thường rất đông. Để khỏi chờ lâu, cô chú nên đặt lịch trước trên website hoặc gọi điện cho phòng khám.""",
                    7));

    private static final String AUTHOR_NAME = "Phòng khám Chúc Từ";

    private final AppProperties config;
    private final UserRepository users;
    private final AuthService auth;
    private final PatientService patientService;
    private final DemoClinic demoClinic;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public Seeder(AppProperties config, UserRepository users, AuthService auth, PatientService patientService,
            DemoClinic demoClinic, JdbcTemplate jdbc, Clock clock) {
        this.config = config;
        this.users = users;
        this.auth = auth;
        this.patientService = patientService;
        this.demoClinic = demoClinic;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public SeedResult run(boolean demo) {
        return run(demo, config);
    }

    /** Như {@link #run(boolean)} nhưng theo cấu hình đã cho (kiểm thử cấu hình production). */
    public SeedResult run(boolean demo, AppProperties settings) {
        User admin = ensureStaff(settings, settings.seedAdmin, "ADMIN", "SEED_ADMIN", true);
        User receptionist = ensureStaff(settings, settings.seedReceptionist, "RECEPTIONIST", "SEED_RECEPTIONIST", false);
        seedPages(admin);
        if (demo) seedDemo(admin, receptionist);
        // Mật khẩu chỉ in ra khi là mật khẩu mẫu (phát triển), không in mật khẩu đặt trong biến môi trường
        log.info("Dữ liệu sẵn sàng. Admin: {}{}{}", login(settings.seedAdmin),
                receptionist != null ? ", Tiếp tân: " + login(settings.seedReceptionist) : "",
                demo ? ". Người dùng mẫu: 0912345678." : ".");
        return new SeedResult(admin, receptionist, jdbc.queryForObject("SELECT COUNT(*) FROM patients", Long.class));
    }

    private static String login(SeedAccount account) {
        return account.password().equals(AppProperties.SAMPLE_STAFF_PASSWORD) ? account.email() + " / " + account.password()
                : account.email();
    }

    /**
     * Tạo tài khoản nội bộ nếu chưa có (theo số điện thoại). Tài khoản đã có thì giữ nguyên, kể cả mật khẩu.
     * Production: không có mật khẩu thì bỏ qua (required: báo lỗi), mật khẩu mẫu hoặc quá ngắn thì báo lỗi.
     */
    private User ensureStaff(AppProperties settings, SeedAccount account, String role, String envPrefix, boolean required) {
        var existing = users.findByPhone(account.phone());
        if (existing.isPresent()) return existing.get();
        String label = Constants.ROLE_LABELS.get(role);
        String password = account.password();
        if (settings.isProd) {
            if (password.isEmpty()) {
                if (required) {
                    throw new IllegalStateException("Đặt " + envPrefix + "_PASSWORD trong biến môi trường để tạo tài khoản " + label
                            + " đầu tiên");
                }
                log.info("Chưa đặt {}_PASSWORD nên không tạo tài khoản {}.", envPrefix, label);
                return null;
            }
            if (password.equals(AppProperties.SAMPLE_STAFF_PASSWORD) || password.length() < Rules.MIN_PASSWORD_LENGTH) {
                throw new IllegalStateException(envPrefix + "_PASSWORD phải khác mật khẩu mẫu và dài ít nhất "
                        + Rules.MIN_PASSWORD_LENGTH + " ký tự khi chạy production");
            }
        }
        User user = new User();
        user.setPhone(account.phone());
        user.setEmail(account.email());
        user.setFullName(account.fullName());
        user.setRole(role);
        user.setPasswordHash(auth.hashPassword(password));
        user.setMustChangePassword(false);
        return users.save(user);
    }

    private void seedPages(User admin) {
        for (Page page : PAGES) {
            Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE slug = ?", Long.class, page.slug());
            if (exists != null && exists > 0) continue;
            insertPost(page.slug(), page.title(), "TRANG", page.summary(), page.content(), admin.getId(), AUTHOR_NAME, "PUBLISHED",
                    now());
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private void insertPost(String slug, String title, String category, String summary, String content, Long authorId,
            String authorName, String status, Instant publishedAt) {
        Instant now = now();
        jdbc.update("INSERT INTO posts (title, slug, category, summary, author_id, author_name, content, status, published_at, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                title, slug, category, summary, authorId, authorName, content, status,
                publishedAt == null ? null : Tx.utc(publishedAt), Tx.utc(now), Tx.utc(now));
    }

    private void seedDemo(User admin, User receptionist) {
        if (users.existsByPhone("0912345678")) return;
        LocalDate today = vn.chuctu.clinic.common.VnTime.today(clock.instant());
        for (DemoPost p : DEMO_POSTS) {
            insertPost(null, p.title(), p.category(), p.summary(), p.content(), admin.getId(), AUTHOR_NAME, "PUBLISHED",
                    now().minus(p.daysAgo(), ChronoUnit.DAYS));
        }
        insertPost(null, "Bản nháp: Buổi khám thiện nguyện trong phường", "TIN_TUC", "Bài viết đang soạn, chưa đăng.", "",
                admin.getId(), admin.getFullName(), "DRAFT", null);

        User user = new User();
        user.setPhone("0912345678");
        user.setFullName("Nguyễn Thị Hoa");
        user.setDob(LocalDate.of(1958, 3, 12));
        user.setGender("NU");
        user.setAddress("Phường 25, Quận Bình Thạnh, TP. Hồ Chí Minh");
        user.setRole("USER");
        user = users.save(user);
        var self = patientService.createPatient(new PatientInput(user.getFullName(), user.getDob(), null, "NU", user.getPhone(),
                user.getAddress(), null, null), user.getId(), "BAN_THAN", user.getId());
        var husband = patientService.createPatient(new PatientInput("Trần Văn Bình", LocalDate.of(1955, 8, 20), null, "NAM",
                "0912345678", null, null, null), user.getId(), "VO_CHONG", user.getId());

        demoClinic.appointment(self, user, user, today.plusDays(2), "09:00", "CHAM_CUU", "ONLINE");
        demoClinic.appointment(husband, user, user, today, "14:00", "VAT_LY_TRI_LIEU", "ONLINE");

        // Vài bệnh nhân đến trực tiếp trong ngày cho màn Lịch trong ngày và Hàng đợi
        Object[][] walkIns = {
            {"Lê Văn Tám", 1950, "0903111222", "08:00", "CHAM_CUU"},
            {"Phạm Thị Lan", 1962, "0903333444", "08:00", "XOA_BOP_BAM_HUYET"},
            {"Võ Minh Châu", 1948, "0903555666", "09:00", "BOC_THUOC"},
            {"Đặng Thị Mai", 1970, "0903777888", "10:00", "CHUA_RO"},
        };
        for (Object[] w : walkIns) {
            var p = patientService.createPatient(new PatientInput((String) w[0], null, (Integer) w[1], null, (String) w[2], null,
                    null, null), null, null, receptionist.getId());
            demoClinic.appointment(p, null, receptionist, today, (String) w[3], (String) w[4], "COUNTER");
        }
        // Lịch trong ngày, hàng đợi, lịch sử khám… cho khu Tiếp tân và Admin
        demoClinic.seed(receptionist);
    }
}
