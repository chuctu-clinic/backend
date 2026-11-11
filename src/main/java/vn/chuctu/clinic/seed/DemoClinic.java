package vn.chuctu.clinic.seed;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;
import vn.chuctu.clinic.common.Counters;
import vn.chuctu.clinic.common.Rules;
import vn.chuctu.clinic.common.Tx;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.patient.Patient;
import vn.chuctu.clinic.patient.PatientService;
import vn.chuctu.clinic.patient.PatientService.PatientInput;
import vn.chuctu.clinic.queue.QueueService;
import vn.chuctu.clinic.queue.Settings;

/**
 * Dữ liệu mẫu cho khu Tiếp tân và Admin, chỉ dùng ở chế độ demo (demoClinic.js). Mọi mốc giờ tính theo lúc khởi động
 * server, nên mở lúc nào cũng có một ngày làm việc "đang diễn ra":
 * <ul>
 * <li>Lịch trong ngày: đủ trạng thái Đã đặt / Đã check-in / Đang khám / Đã hoàn thành / Đã huỷ, cả online lẫn tại quầy.
 * <li>Hàng đợi: 4/5 chỗ có người (chỗ 3 trống), người chờ nhóm ưu tiên và nhóm thường; tắt tự động mời.
 * <li>Hồ sơ bệnh nhân: ~40 hồ sơ, lịch sử khám 2 tháng kèm phiếu kết quả, 1 hồ sơ bị khoá, vài hồ sơ trùng số điện thoại.
 * <li>Các ngày tới: phiếu đã đặt để thử đặt hộ, đổi giờ; khung 9:00 ngày mai đã đủ chỗ.
 * </ul>
 * Phiếu và check-in được ghi thẳng vào CSDL (không qua luật "khung giờ đã qua"), nên mở buổi tối vẫn có dữ liệu. Mọi phiếu
 * chưa huỷ đều tính vào bộ đếm khung giờ và không khung nào vượt sức chứa, để số chỗ trống hiển thị đúng.
 */
@Component
public class DemoClinic {

    private static final long MIN = 60_000L;
    private static final List<String> SLOTS = Rules.SLOTS;

    private static final List<String> WARDS = List.of(
            "Phường Bình Thạnh, TP. Hồ Chí Minh", "Phường Gia Định, TP. Hồ Chí Minh", "Phường Thạnh Mỹ Tây, TP. Hồ Chí Minh",
            "Phường Bình Lợi Trung, TP. Hồ Chí Minh", "Phường Sài Gòn, TP. Hồ Chí Minh", "Phường Bến Thành, TP. Hồ Chí Minh",
            "Phường Hạnh Thông, TP. Hồ Chí Minh", "Xã Long Thành, Tỉnh Đồng Nai");
    private static final List<String> STREETS = List.of("Điện Biên Phủ", "Xô Viết Nghệ Tĩnh", "Nơ Trang Long", "Bạch Đằng",
            "Phan Đăng Lưu", "Lê Quang Định", "Nguyễn Xí", "Đinh Bộ Lĩnh");

    private record Person(String fullName, String gender, int year) {}

    private static final List<Person> PEOPLE = List.of(
            new Person("Lý Thị Ngọc", "NU", 1951), new Person("Trương Văn Phúc", "NAM", 1946),
            new Person("Huỳnh Thị Sen", "NU", 1955), new Person("Đỗ Văn Lộc", "NAM", 1960),
            new Person("Mai Thị Hồng", "NU", 1949), new Person("Phan Văn Tài", "NAM", 1958),
            new Person("Bùi Thị Thu", "NU", 1963), new Person("Hồ Văn Sơn", "NAM", 1952),
            new Person("Lâm Thị Kim", "NU", 1957), new Person("Dương Văn Hải", "NAM", 1944),
            new Person("Châu Thị Nga", "NU", 1966), new Person("Tạ Văn Quang", "NAM", 1950),
            new Person("Quách Thị Yến", "NU", 1959), new Person("Kiều Văn Đức", "NAM", 1947),
            new Person("Tô Thị Hương", "NU", 1968), new Person("Lưu Văn Thành", "NAM", 1955),
            new Person("Vương Thị Liên", "NU", 1945), new Person("Cao Văn Minh", "NAM", 1962),
            new Person("Đinh Thị Xuân", "NU", 1953), new Person("La Văn Bảo", "NAM", 1971),
            new Person("Trịnh Thị Cúc", "NU", 1948), new Person("Hà Văn Nghĩa", "NAM", 1956),
            new Person("Từ Thị Mận", "NU", 1961), new Person("Ông Văn Tư", "NAM", 1943),
            new Person("Thái Thị Hoa", "NU", 1964), new Person("Diệp Văn Long", "NAM", 1969),
            new Person("Âu Thị Lành", "NU", 1950), new Person("Mạc Văn Toàn", "NAM", 1958),
            new Person("Lương Thị Bích", "NU", 1975), new Person("Phùng Văn Khải", "NAM", 1954));

    /** Mẫu phiếu kết quả khám: nội dung dịch vụ, chẩn đoán, mạch, huyết áp, ghi chú điều trị (châm cứu, bấm huyệt, khác). */
    private record RecordTemplate(String service, String diagnosis, String pulse, String bloodPressure, String acupuncture,
            String acupressure, String other) {}

    private static final List<RecordTemplate> RECORDS = List.of(
            new RecordTemplate("CHAM_CUU", "Đau mỏi vai gáy, tê tay phải do thoái hoá đốt sống cổ.", "78", "130/80",
                    "Châm cứu vùng vai gáy và tay phải, lưu kim 20 phút.", "Xoa bóp bấm huyệt vùng vai gáy.", null),
            new RecordTemplate("CHAM_CUU", "Đau thắt lưng, đau lan xuống chân trái.", "72", "140/85",
                    "Châm cứu vùng thắt lưng và chân trái, lưu kim 20 phút.", null, "Nằm giường cứng, tránh bê vác nặng."),
            new RecordTemplate("XOA_BOP_BAM_HUYET", "Co cứng cơ vùng lưng, mỏi vai do ngồi lâu.", "76", "125/80", null,
                    "Xoa bóp bấm huyệt vùng lưng và vai, 30 phút.", "Tập vươn vai nhẹ mỗi giờ khi ngồi làm việc."),
            new RecordTemplate("VAT_LY_TRI_LIEU", "Thoái hoá khớp gối hai bên, đi lại khó khăn.", "80", "135/85", null, null,
                    "Tập vận động khớp gối, chườm nóng 15 phút. Tập đi có người đỡ."),
            new RecordTemplate("BOC_THUOC", "Mệt mỏi, ăn ngủ kém, khí huyết yếu.", "82", "120/75", null, null,
                    "Bốc 7 thang thuốc Nam bổ khí huyết, sắc uống sau ăn sáng và tối."),
            new RecordTemplate("CHAM_CUU", "Di chứng tai biến, yếu nửa người trái.", "74", "150/90",
                    "Châm cứu phục hồi tay chân bên trái, lưu kim 25 phút.", "Xoa bóp tay chân bên trái.",
                    "Tập cầm nắm bằng tay trái tại nhà."),
            new RecordTemplate("CHUA_RO", "Mất ngủ kéo dài, hay đau đầu về chiều.", "84", "128/82",
                    "Châm cứu an thần, lưu kim 20 phút.", null, "Hạn chế trà, cà phê sau 15 giờ."),
            new RecordTemplate("XOA_BOP_BAM_HUYET", "Chuột rút ban đêm, tê bì hai bàn chân.", "70", "118/76", null,
                    "Bấm huyệt hai chân, ngâm chân nước ấm.", "Uống đủ nước, ngâm chân nước ấm trước khi ngủ."));

    private static final List<String> HISTORIES = java.util.Arrays.asList(
            "Cao huyết áp: đang uống thuốc hạ huyết áp mỗi sáng.", "Đái tháo đường típ 2, uống thuốc theo toa.",
            "Phẫu thuật, chấn thương: gãy tay trái năm 2015.", "Dị ứng thuốc: dị ứng penicillin.",
            "Hút thuốc lá hơn 20 năm, đã bỏ được 3 năm.", null, null);
    private static final List<String> DOCTORS = List.of("BS. Nguyễn Văn An", "YS. Trần Thị Bích", "BS. Lê Minh Tuấn");
    private static final List<String> SERVICES = List.of("CHAM_CUU", "XOA_BOP_BAM_HUYET", "VAT_LY_TRI_LIEU", "BOC_THUOC", "CHUA_RO");
    private static final List<String> CANCEL_REASONS = List.of("Bận việc đột xuất", "Sức khoẻ không đi được",
            "Muốn đổi sang ngày giờ khác");

    /** Hồ sơ cùng tài khoản đặt (user null: khách đến trực tiếp). */
    private record Who(Patient patient, User user) {}

    private record Cancel(String reason, User by, Instant at) {}

    private record Appt(long id, long patientId, String slotStart) {}

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PatientService patients;
    private final Counters counters;
    private final Settings settings;
    private final Clock clock;

    /** Số giả ngẫu nhiên cố định để mỗi lần khởi động ra cùng một bộ dữ liệu. */
    private long state;

    public DemoClinic(JdbcTemplate jdbc, UserRepository users, PatientService patients, Counters counters, Settings settings,
            Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.patients = patients;
        this.counters = counters;
        this.settings = settings;
        this.clock = clock;
    }

    private double rand() {
        state = (state * 1103515245L + 12345L) % 2147483648L;
        return state / 2147483648.0;
    }

    private <T> T pick(List<T> list) {
        return list.get((int) Math.floor(rand() * list.size()));
    }

    private int between(int a, int b) {
        return a + (int) Math.floor(rand() * (b - a + 1));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static Instant plusMinutes(Instant t, long minutes) {
        return t.plusMillis(minutes * MIN);
    }

    /** Khung giờ đang diễn ra lúc khởi động (ngoài giờ làm việc thì lấy khung gần nhất). */
    private static String currentSlot(Instant now) {
        int m = VnTime.minutesOfDay(now);
        String found = null;
        for (String s : SLOTS) if (VnTime.hhmmToMinutes(s) <= m) found = s;
        return found != null ? found : SLOTS.get(0);
    }

    private int slotCount(LocalDate date, String start) {
        List<Integer> rows = jdbc.query("SELECT `count` FROM slot_counters WHERE slot_date = ? AND start_time = ?",
                (rs, i) -> rs.getInt(1), date, start);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /** Chọn khung còn chỗ, ưu tiên các khung trong danh sách; null nếu ngày đã kín. */
    private String freeSlot(LocalDate date, List<String> preferred) {
        List<String> order = new ArrayList<>(preferred);
        order.addAll(SLOTS);
        for (String start : order) if (slotCount(date, start) < Rules.SLOT_CAPACITY) return start;
        return null;
    }

    private long insertReturningId(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private Patient createPatient(PatientInput input, User account, String relationship, User createdBy) {
        return patients.createPatient(input, account == null ? null : account.getId(), relationship, createdBy.getId());
    }

    private Appt makeAppt(Patient patient, User user, User createdBy, LocalDate date, String start, String service, String source,
            String status, Instant createdAt, Cancel cancel) {
        if (!status.equals("CANCELLED")) {
            // Giữ chỗ trong bộ đếm khung giờ như đặt lịch thật
            jdbc.update("INSERT INTO slot_counters (slot_date, start_time, `count`) VALUES (?, ?, 1) "
                    + "ON DUPLICATE KEY UPDATE `count` = `count` + 1", date, start);
        }
        long seq = counters.next("ticket-" + date);
        String ticket = "CT-PK-" + VnTime.yy(date) + "-" + VnTime.ddmm(date) + "-" + String.format("%03d", seq);
        Instant slotInstant = VnTime.slotInstant(date, start);
        Instant created = createdAt != null ? createdAt : slotInstant.minus(between(1, 6), ChronoUnit.DAYS);
        long id = insertReturningId("INSERT INTO appointments (ticket_code, user_id, patient_id, service_type, slot_date, slot_start, "
                + "expected_time, status, source, created_by, cancel_reason, cancelled_by, cancelled_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ticket, user == null ? null : user.getId(), patient.getId(), service, date, start, Tx.utc(slotInstant), status,
                source, createdBy.getId(), cancel == null ? null : cancel.reason(), cancel == null ? null : cancel.by().getId(),
                cancel == null ? null : Tx.utc(cancel.at()), Tx.utc(created), Tx.utc(now()));
        return new Appt(id, patient.getId(), start);
    }

    private long makeCheckin(Appt appt, LocalDate date, Instant checkInTime, String priority, String status, Integer seatNo,
            Instant enteredAt, Instant completedAt) {
        return insertReturningId("INSERT INTO checkins (appointment_id, patient_id, visit_date, check_in_time, priority_group, status, "
                + "seat_no, entered_at, completed_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                appt.id(), appt.patientId(), date, Tx.utc(checkInTime), priority, status, seatNo,
                enteredAt == null ? null : Tx.utc(enteredAt), completedAt == null ? null : Tx.utc(completedAt), Tx.utc(now()));
    }

    private void makeRecord(Appt appt, Long checkinId, LocalDate date, User staff, RecordTemplate tpl, String history) {
        LocalDate followUp = rand() < 0.6 ? date.plusDays(pick(List.of(7, 10, 14))) : null;
        // Phiếu được xác nhận sau giờ khám khoảng 40 phút, để hạn sửa 24 giờ tính đúng theo ngày khám
        Instant confirmedAt = plusMinutes(VnTime.slotInstant(date, appt.slotStart()), 40);
        if (confirmedAt.isAfter(now())) confirmedAt = now();
        jdbc.update("INSERT INTO medical_records (patient_id, appointment_id, checkin_id, visit_date, diagnosis, pulse, blood_pressure, "
                + "treatment_acupuncture, treatment_acupressure, treatment_other, medical_history, follow_up_date, doctor_name, "
                + "recorded_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                appt.patientId(), appt.id(), checkinId, date, tpl.diagnosis(), tpl.pulse(), tpl.bloodPressure(), tpl.acupuncture(),
                tpl.acupressure(), tpl.other(), history, followUp, pick(DOCTORS), staff.getId(), Tx.utc(confirmedAt),
                Tx.utc(confirmedAt));
    }

    /** Một lượt khám đã xong trong quá khứ: phiếu Đã hoàn thành, check-in, phiếu kết quả. Khung kín thì bỏ qua lượt này. */
    private void pastVisit(Patient patient, User user, User staff, LocalDate date, String history) {
        RecordTemplate tpl = pick(RECORDS);
        String start = freeSlot(date, List.of(pick(SLOTS)));
        boolean online = user != null && rand() < 0.6;
        if (start == null) return;
        Appt appt = makeAppt(patient, online ? user : null, online ? user : staff, date, start, tpl.service(),
                online ? "ONLINE" : "COUNTER", "COMPLETED", null, null);
        Instant checkInTime = plusMinutes(VnTime.slotInstant(date, start), between(-10, 20));
        Instant enteredAt = plusMinutes(checkInTime, between(5, 30));
        long checkin = makeCheckin(appt, date, checkInTime, online ? "PRIORITY" : "NORMAL", "COMPLETED", between(1, 5), enteredAt,
                plusMinutes(enteredAt, between(20, 45)));
        makeRecord(appt, checkin, date, staff, tpl, history);
    }

    /** Phiếu mẫu cho một người dùng demo, bỏ qua kiểm tra giờ. Có giữ chỗ trong bộ đếm khung giờ. */
    public void appointment(Patient patient, User user, User createdBy, LocalDate date, String start, String service, String source) {
        makeAppt(patient, user, createdBy, date, start, service, source, "BOOKED", now().minus(2, ChronoUnit.DAYS), null);
    }

    /** Chọn lần lượt trong danh sách; unique thì mỗi hồ sơ chỉ được chọn một lần trong ngày hôm nay. */
    private final class Picker {
        private final List<Who> list;
        private final Set<Long> used;
        private int i;

        Picker(List<Who> list, Set<Long> used) {
            this.list = list;
            this.used = used;
        }

        Who take(boolean unique) {
            for (int k = 0; k < list.size(); k++) {
                Who e = list.get(i++ % list.size());
                if (!unique || !used.contains(e.patient().getId())) {
                    if (unique) used.add(e.patient().getId());
                    return e;
                }
            }
            return list.get(i++ % list.size());
        }

        Who take() {
            return take(true);
        }
    }

    public void seed(User receptionist) {
        state = 20261021;
        Instant now = now();
        LocalDate today = VnTime.today(now);
        String cur = currentSlot(now);
        int curIdx = SLOTS.indexOf(cur);
        List<String> earlier = SLOTS.subList(0, Math.max(1, curIdx));
        List<String> later = SLOTS.subList(curIdx + 1, SLOTS.size()).isEmpty() ? List.of(cur)
                : SLOTS.subList(curIdx + 1, SLOTS.size());

        // ---------- Tài khoản người dùng và hồ sơ ----------
        List<Who> accounts = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Person p = PEOPLE.get(i);
            String phone = "09080000" + String.format("%02d", i + 1);
            LocalDate dob = LocalDate.of(p.year(), between(1, 12), between(1, 28));
            String address = between(5, 220) + " " + pick(STREETS) + ", " + pick(WARDS);
            User user = new User();
            user.setPhone(phone);
            user.setFullName(p.fullName());
            user.setGender(p.gender());
            user.setDob(dob);
            user.setAddress(address);
            user.setRole("USER");
            user = users.save(user);
            Patient self = createPatient(new PatientInput(p.fullName(), dob, null, p.gender(), phone, address, null, null), user,
                    "BAN_THAN", user);
            accounts.add(new Who(self, user));
        }
        // Hai tài khoản có thêm hồ sơ người thân
        User u1 = accounts.get(1).user();
        User u2 = accounts.get(2).user();
        accounts.add(new Who(createPatient(new PatientInput("Trương Thị Mỹ", null, 1950, "NU", u1.getPhone(), null, null, null), u1,
                "VO_CHONG", u1), u1));
        accounts.add(new Who(createPatient(new PatientInput("Huỳnh Văn Lợi", null, 1983, "NAM", "0908100200", null, null, null), u2,
                "CON", u2), u2));

        // Hồ sơ do tiếp tân lập tại quầy (chỉ cần họ tên, năm sinh, số điện thoại)
        List<Patient> walkIns = new ArrayList<>();
        for (int i = 0; i < PEOPLE.size() - 16; i++) {
            Person p = PEOPLE.get(16 + i);
            String phone = "09070001" + String.format("%02d", i + 10);
            String address = rand() < 0.7 ? between(5, 220) + " " + pick(STREETS) + ", " + pick(WARDS) : null;
            walkIns.add(createPatient(new PatientInput(p.fullName(), null, p.year(), p.gender(), phone, address, null, null), null,
                    null, receptionist));
        }
        // Trùng số điện thoại với hồ sơ có sẵn: thử cảnh báo trùng khi lập hồ sơ mới
        String dupPhone = walkIns.get(0).getPhone();
        walkIns.add(createPatient(new PatientInput("Lý Văn Hoà", null, 1949, "NAM", dupPhone, null, null, null), null, null,
                receptionist));
        walkIns.add(createPatient(new PatientInput("Lý Thị Ngọc", null, 1952, "NU", "0907000199", null, null, null), null, null,
                receptionist));

        // Hồ sơ bị khoá: có lịch sử khám, không đặt lịch / check-in được nữa
        Patient locked = createPatient(new PatientInput("Nguyễn Văn Khoá", null, 1957, "NAM", "0907000177",
                "12 Bạch Đằng, " + WARDS.get(0), null, null), null, null, receptionist);

        // ---------- Lịch sử khám 2 tháng gần đây ----------
        List<Who> everyone = new ArrayList<>(accounts);
        for (Patient p : walkIns) everyone.add(new Who(p, null));
        for (Who w : everyone) {
            if (rand() < 0.25) continue;
            String history = pick(HISTORIES);
            int visits = between(1, 4);
            Set<Integer> daySet = new HashSet<>();
            for (int k = 0; k < visits; k++) daySet.add(between(3, 60));
            List<Integer> days = new ArrayList<>(daySet);
            days.sort((a, b) -> b - a);
            for (int d : days) pastVisit(w.patient(), w.user(), receptionist, today.minusDays(d), history);
            // Thỉnh thoảng có phiếu đã huỷ trong quá khứ
            if (rand() < 0.2) {
                LocalDate d = today.minusDays(between(2, 40));
                User by = w.user() != null ? w.user() : receptionist;
                makeAppt(w.patient(), w.user(), by, d, pick(SLOTS), pick(SERVICES), w.user() != null ? "ONLINE" : "COUNTER",
                        "CANCELLED", null, new Cancel(pick(CANCEL_REASONS), by,
                                VnTime.slotInstant(d, "08:00").minus(1, ChronoUnit.DAYS)));
            }
        }
        for (int d : new int[] {45, 21}) pastVisit(locked, null, receptionist, today.minusDays(d), HISTORIES.get(0));
        jdbc.update("UPDATE patients SET is_locked = 1, locked_at = ? WHERE id = ?", Tx.utc(now.minus(3, ChronoUnit.DAYS)),
                locked.getId());

        // ---------- Hôm nay ----------
        // Mỗi người chỉ có một phiếu trong ngày hôm nay; các ngày tới thì không cần chặn
        Set<Long> usedToday = new HashSet<>();
        // Khách đến trực tiếp (không tài khoản) và người đặt online lấy từ hai nhóm riêng
        Picker take = new Picker(walkIns.stream().map(p -> new Who(p, null)).toList(), usedToday);
        Picker takeOnline = new Picker(everyone.stream().filter(e -> e.user() != null).toList(), usedToday);

        // Đã khám xong sáng nay: phiếu Đã hoàn thành kèm phiếu kết quả
        for (int i = 0; i < 6; i++) {
            Who who = i % 2 == 1 ? take.take() : takeOnline.take();
            String start = freeSlot(today, earlier);
            RecordTemplate tpl = pick(RECORDS);
            Appt appt = todayAppt(who, receptionist, today, start, "COMPLETED", tpl.service(), null);
            Instant checkInTime = now.minusMillis((150 + i * 15L) * MIN);
            Instant enteredAt = plusMinutes(checkInTime, between(5, 20));
            long checkin = makeCheckin(appt, today, checkInTime, who.user() != null ? "PRIORITY" : "NORMAL", "COMPLETED", (i % 5) + 1,
                    enteredAt, plusMinutes(enteredAt, between(20, 40)));
            makeRecord(appt, checkin, today, receptionist, tpl, pick(HISTORIES));
        }

        // Đang trong phòng khám: chỗ 1, 2, 4, 5 (chỗ 3 vừa trống)
        int[] seats = {1, 2, 4, 5};
        for (int i = 0; i < seats.length; i++) {
            Who who = i % 2 == 1 ? take.take() : takeOnline.take();
            // Lấy khung liền trước để khung hiện tại còn chỗ cho người chờ nhóm ưu tiên
            List<String> preferred = new ArrayList<>(earlier.subList(earlier.size() - 1, earlier.size()));
            preferred.add(cur);
            String start = freeSlot(today, preferred);
            Appt appt = todayAppt(who, receptionist, today, start, "IN_PROGRESS", pick(SERVICES), null);
            long checkinId = makeCheckin(appt, today, now.minusMillis((45 + i * 8L) * MIN), who.user() != null ? "PRIORITY" : "NORMAL",
                    "IN_ROOM", seats[i], now.minusMillis((5 + i * 9L) * MIN), null);
            // Người đang ở chỗ điều trị đã được bác sĩ khám sơ bộ và chỉ định
            jdbc.update("UPDATE checkins SET exam_started_at = ?, prelim_diagnosis = ?, treatment_kinds = ?, treatment_order = ?, ordered_at = ? "
                    + "WHERE id = ?", Tx.utc(now.minusMillis((25 + i * 9L) * MIN)), "Đau mỏi cổ vai gáy", "CHAM_CUU,XOA_BOP_BAM_HUYET",
                    "Châm cứu vùng cổ vai, xoa bóp 20 phút", Tx.utc(now.minusMillis((10 + i * 9L) * MIN)), checkinId);
        }

        // Đang chờ: 3 người nhóm ưu tiên (đặt trước, đến đúng khung giờ), 3 người nhóm thường (khách đến trực tiếp)
        for (int i = 0; i < 6; i++) {
            boolean priority = i < 3;
            Who who = priority ? takeOnline.take() : take.take();
            // Nhóm ưu tiên phải đúng khung hiện tại; nhóm thường lấy khung nào còn chỗ
            List<String> preferred = new ArrayList<>();
            preferred.add(cur);
            if (!priority) {
                List<String> back = new ArrayList<>(earlier);
                java.util.Collections.reverse(back);
                preferred.addAll(back);
            }
            String start = freeSlot(today, preferred);
            Appt appt = todayAppt(priority ? who : new Who(who.patient(), null), receptionist, today, start, "CHECKED_IN",
                    pick(SERVICES), null);
            long ago = priority ? 30 - i * 8L : 40 - (i - 3) * 9L;
            makeCheckin(appt, today, now.minusMillis(ago * MIN), priority ? "PRIORITY" : "NORMAL", "WAITING", null, null, null);
        }

        // Chưa tới: các khung sau trong ngày, và hai người đặt khung trước nhưng chưa đến
        for (int i = 0; i < 7; i++) {
            Who who = i % 3 == 2 ? take.take() : takeOnline.take();
            String start = freeSlot(today, i < 2 ? earlier : later);
            if (start != null) todayAppt(who, receptionist, today, start, "BOOKED", pick(SERVICES), null);
        }

        // Đã huỷ hôm nay: một người tự huỷ trên web, một phiếu tiếp tân huỷ giúp qua điện thoại
        Who c1 = takeOnline.take();
        todayAppt(c1, receptionist, today, later.get(0), "CANCELLED", pick(SERVICES),
                new Cancel("Sức khoẻ không đi được", c1.user(), now.minusMillis(180 * MIN)));
        todayAppt(new Who(take.take().patient(), null), receptionist, today, later.get(later.size() - 1), "CANCELLED",
                pick(SERVICES), new Cancel("Gọi điện báo bận việc đột xuất", receptionist, now.minusMillis(90 * MIN)));

        // ---------- Các ngày tới ----------
        // Khung 9:00 ngày mai kín chỗ để thử "Đã đủ chỗ"
        LocalDate tomorrow = today.plusDays(1);
        while ("09:00".equals(freeSlot(tomorrow, List.of("09:00")))) {
            Who who = takeOnline.take(false);
            makeAppt(who.patient(), who.user(), who.user(), tomorrow, "09:00", pick(SERVICES), "ONLINE", "BOOKED", null, null);
        }
        for (int d = 1; d <= 6; d++) {
            LocalDate date = today.plusDays(d);
            int n = between(3, 5);
            for (int i = 0; i < n; i++) {
                Who who = rand() < 0.6 ? takeOnline.take(false) : new Who(take.take(false).patient(), null);
                String start = freeSlot(date, List.of(pick(SLOTS)));
                if (start == null) continue;
                makeAppt(who.patient(), who.user(), who.user() != null ? who.user() : receptionist, date, start, pick(SERVICES),
                        who.user() != null ? "ONLINE" : "COUNTER", "BOOKED", null, null);
            }
            if (d % 2 == 0) {
                Who who = takeOnline.take(false);
                makeAppt(who.patient(), who.user(), who.user(), date, pick(SLOTS), pick(SERVICES), "ONLINE", "CANCELLED", null,
                        new Cancel(pick(CANCEL_REASONS), who.user(), now.minusMillis(between(60, 1440) * MIN)));
            }
        }

        // Chỗ 3 trống và còn người chờ: tắt tự động mời để tiếp tân bấm "Mời người kế tiếp"
        settings.set(QueueService.AUTO_KEY, false);
    }

    private Appt todayAppt(Who who, User receptionist, LocalDate today, String start, String status, String service, Cancel cancel) {
        boolean online = who.user() != null;
        // Khách đến trực tiếp được lập phiếu ngay trong ngày
        Instant createdAt = online ? null : now().minusMillis(between(60, 240) * MIN);
        return makeAppt(who.patient(), who.user(), online ? who.user() : receptionist, today, start, service,
                online ? "ONLINE" : "COUNTER", status, createdAt, cancel);
    }
}
