package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.common.VnTime;

/** Cổng từ test/booking.test.js (đặt lịch, đặt hộ và đổi giờ tại quầy), thêm test [java] cho giờ gần nửa đêm. */
class BookingTest extends IntegrationTest {

    static String futureDate(int days) {
        return VnTime.today(Instant.now()).plusDays(days).toString();
    }

    static TestClient.Result book(TestClient agent, String patientId, String slotDate, String slotStart) {
        return agent.post("/api/me/appointments",
                body("patientId", patientId, "serviceType", "CHAM_CUU", "slotDate", slotDate, "slotStart", slotStart));
    }

    static TestClient.Result book(TestClient agent, String patientId, String slotDate) {
        return book(agent, patientId, slotDate, "09:00");
    }

    static String code(TestClient.Result res) {
        return res.json().get("error").get("code").stringValue();
    }

    /** Chạy n việc cùng lúc trên n luồng thật (cùng chờ hiệu lệnh xuất phát). */
    static <T> List<T> parallel(int n, java.util.function.IntFunction<T> job) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                Callable<T> task = () -> {
                    start.await();
                    return job.apply(index);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("đặt lịch xong có ngay phiếu khám với mã duy nhất (BR.15, BR.16)")
    void datLichXongCoPhieu() {
        var agent = client().registerUser();
        var res = book(agent, agent.selfPatientId(), futureDate(2)).expectStatus(201);
        var appt = res.json().get("appointment");
        assertThat(appt.get("ticketCode").stringValue()).matches("^CT-PK-\\d{2}-\\d{4}-\\d{3}$");
        assertThat(appt.get("status").stringValue()).isEqualTo("BOOKED");
        assertThat(appt.get("slotStart").stringValue()).isEqualTo("09:00");
    }

    @Test
    @DisplayName("không đặt quá 30 ngày tới hoặc ngày đã qua (BR.13)")
    void khongDatQua30Ngay() {
        var agent = client().registerUser();
        String patientId = agent.selfPatientId();
        book(agent, patientId, futureDate(31)).expectStatus(400);
        book(agent, patientId, futureDate(-1)).expectStatus(400);
    }

    @Test
    @DisplayName("mỗi khung giờ nhận tối đa 6 phiếu, kể cả khi nhiều người đặt cùng lúc (BR.14, QAs6)")
    void moiKhungToiDa6Phieu() throws Exception {
        String date = futureDate(3);
        List<TestClient> agents = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            var agent = client().registerUser("091200000" + i, "Người " + i);
            agents.add(agent);
            ids.add(agent.selfPatientId());
        }
        List<TestClient.Result> results = parallel(agents.size(), i -> book(agents.get(i), ids.get(i), date, "10:00"));
        long ok = results.stream().filter(r -> r.status() == 201).count();
        long full = results.stream().filter(r -> r.status() == 409 && code(r).equals("SLOT_FULL")).count();
        assertThat(ok).isEqualTo(6);
        assertThat(full).as(results.stream().map(TestClient.Result::text).toList().toString()).isEqualTo(3);

        var avail = agents.get(0).get("/api/me/availability?date=" + date).expectStatus(200).json();
        JsonNode slot = null;
        for (JsonNode s : avail.get("slots")) if (s.get("start").stringValue().equals("10:00")) slot = s;
        assertThat(slot.get("available").booleanValue()).isFalse();
        assertThat(slot.get("reason").stringValue()).isEqualTo("Đã đủ chỗ");
    }

    @Test
    @DisplayName("huỷ phiếu trả lại chỗ cho khung giờ và đặt lại được (BR.18)")
    void huyPhieuTraLaiCho() {
        String date = futureDate(4);
        List<TestClient> agents = new ArrayList<>();
        List<JsonNode> tickets = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            var agent = client().registerUser("091300000" + i, "Người " + i);
            agents.add(agent);
            tickets.add(book(agent, agent.selfPatientId(), date, "11:00").expectStatus(201).json().get("appointment"));
        }
        var extra = client().registerUser("0913000099", "Người Thêm");
        String extraId = extra.selfPatientId();
        book(extra, extraId, date, "11:00").expectStatus(409);

        var cancelled = agents.get(0).post("/api/me/appointments/" + tickets.get(0).get("id").stringValue() + "/cancel",
                body("reason", "Bận việc")).expectStatus(200).json();
        assertThat(cancelled.get("appointment").get("status").stringValue()).isEqualTo("CANCELLED");
        book(extra, extraId, date, "11:00").expectStatus(201);
    }

    @Test
    @DisplayName("chỉ xem được phiếu khám của mình (BR.20)")
    void chiXemPhieuCuaMinh() {
        var a = client().registerUser("0914000001", "Người A");
        var b = client().registerUser("0914000002", "Người B");
        var appt = book(a, a.selfPatientId(), futureDate(2)).expectStatus(201).json().get("appointment");
        String id = appt.get("id").stringValue();
        b.get("/api/me/appointments/" + id).expectStatus(404);
        b.post("/api/me/appointments/" + id + "/cancel", body()).expectStatus(404);
    }

    @Test
    @DisplayName("hồ sơ người thân lưu số căn cước, email; phiếu khám có đủ thông tin người đi khám")
    void hoSoNguoiThanCoDuThongTin() {
        var agent = client().registerUser();
        var bad = agent.post("/api/me/patients", body("fullName", "Trần Văn Bảy", "dob", "1949-11-08", "gender", "NAM",
                "relationship", "VO_CHONG", "nationalId", "123", "email", "sai")).expectStatus(400);
        assertThat(bad.json().get("error").get("fields").has("nationalId")).isTrue();
        assertThat(bad.json().get("error").get("fields").has("email")).isTrue();

        var created = agent.post("/api/me/patients", body("fullName", "Trần Văn Bảy", "dob", "1949-11-08", "gender", "NAM",
                "relationship", "VO_CHONG", "nationalId", "079049000123", "email", "Bay@Example.com", "address", "12 Nguyễn Văn Đậu"))
                .expectStatus(201).json();
        assertThat(created.get("patient").get("nationalId").stringValue()).isEqualTo("079049000123");
        assertThat(created.get("patient").get("email").stringValue()).isEqualTo("bay@example.com");

        var yearOnly = agent.post("/api/me/patients", body("fullName", "Nguyễn Minh Khang", "phone", "0912000111", "birthYear", 1981,
                "gender", "NAM", "relationship", "CON")).expectStatus(201).json();
        assertThat(yearOnly.get("patient").get("birthYear").intValue()).isEqualTo(1981);
        assertThat(yearOnly.get("patient").get("dob").isNull()).isTrue();
        var noBirth = agent.post("/api/me/patients", body("fullName", "Không Ngày Sinh", "gender", "NU", "relationship", "CON"))
                .expectStatus(400);
        assertThat(noBirth.json().get("error").get("fields").has("dob")).isTrue();

        for (JsonNode p : agent.get("/api/me/patients").expectStatus(200).json().get("items")) assertThat(p.has("lastVisitDate")).isTrue();

        var res = book(agent, created.get("patient").get("id").stringValue(), futureDate(2)).expectStatus(201).json();
        var patient = res.get("appointment").get("patient");
        assertThat(patient.get("address").stringValue()).isEqualTo("12 Nguyễn Văn Đậu");
        assertThat(patient.get("dob").stringValue()).startsWith("1949-11-08");
    }

    @Test
    @DisplayName("mỗi tài khoản tối đa 10 hồ sơ khám (BR.12)")
    void toiDa10HoSo() {
        var agent = client().registerUser();
        for (int i = 0; i < 9; i++) {
            agent.post("/api/me/patients", body("fullName", "Người thân " + i, "dob", "1990-01-01", "gender", "NAM", "relationship", "CON"))
                    .expectStatus(201);
        }
        var res = agent.post("/api/me/patients", body("fullName", "Người thứ 11", "dob", "1990-01-01", "gender", "NAM",
                "relationship", "CON")).expectStatus(400);
        assertThat(code(res)).isEqualTo("PROFILE_LIMIT");
    }

    @Test
    @DisplayName("hồ sơ bị khoá không đặt lịch được (BR.19)")
    void hoSoBiKhoaKhongDatDuoc() {
        var agent = client().registerUser();
        String patientId = agent.selfPatientId();
        var admin = client().staff("admin@chuctu.vn");
        admin.post("/api/admin/patients/" + patientId + "/lock", Map.of()).expectStatus(200);
        var res = book(agent, patientId, futureDate(2)).expectStatus(403);
        assertThat(res.json().get("error").get("message").stringValue()).contains("khoá");
    }

    @Test
    @DisplayName("người dùng chỉ thấy trạng thái Đã đặt lịch dù phiếu đã check-in (BR.17)")
    void nguoiDungChiThayDaDatLich() {
        var agent = client().registerUser();
        String patientId = agent.selfPatientId();
        var staff = client().staff();
        var patient = staff.get("/api/staff/patients/" + patientId).expectStatus(200).json();
        var counter = staff.post("/api/staff/appointments", body("patientId", patient.get("patient").get("id").stringValue(),
                "serviceType", "CHAM_CUU", "slotDate", futureDate(1), "slotStart", "09:00")).expectStatus(201).json();
        var mine = agent.get("/api/me/appointments/" + counter.get("appointment").get("id").stringValue()).expectStatus(200).json();
        assertThat(mine.get("appointment").get("status").stringValue()).isEqualTo("BOOKED");
    }

    // ---------- Đặt hộ và đổi giờ tại quầy ----------

    private String staffPatient(TestClient staff, String name, String phone) {
        return staff.post("/api/staff/patients", body("fullName", name, "birthYear", 1960, "phone", phone))
                .expectStatus(201).json().get("patient").get("id").stringValue();
    }

    @Test
    @DisplayName("tiếp tân tạo hồ sơ được cảnh báo trùng số điện thoại (BR.25, BR.35)")
    void canhBaoTrungSoDienThoai() {
        var staff = client().staff();
        staff.post("/api/staff/patients", body("fullName", "Lê Văn B", "birthYear", 1960, "phone", "0915000001")).expectStatus(201);
        var dup = staff.post("/api/staff/patients", body("fullName", "Lê Văn C", "birthYear", 1961, "phone", "0915000001")).expectStatus(409);
        assertThat(code(dup)).isEqualTo("DUPLICATE_PHONE");
        assertThat(dup.json().get("error").get("duplicates").size()).isEqualTo(1);
        staff.post("/api/staff/patients", body("fullName", "Lê Văn C", "birthYear", 1961, "phone", "0915000001",
                "confirmDuplicate", true)).expectStatus(201);
    }

    @Test
    @DisplayName("tiếp tân chỉ đặt hộ trong vòng 7 ngày (BR.13)")
    void datHoTrong7Ngay() {
        var staff = client().staff();
        String id = staffPatient(staff, "Lê Văn D", "0915000002");
        java.util.function.Function<String, TestClient.Result> send = date -> staff.post("/api/staff/appointments",
                body("patientId", id, "serviceType", "BOC_THUOC", "slotDate", date, "slotStart", "15:00"));
        send.apply(futureDate(8)).expectStatus(400);
        var ok = send.apply(futureDate(7)).expectStatus(201).json();
        assertThat(ok.get("appointment").get("source").stringValue()).isEqualTo("COUNTER");
    }

    @Test
    @DisplayName("đổi giờ huỷ phiếu cũ và tạo phiếu mới trỏ về phiếu cũ (BR.26)")
    void doiGioHuyPhieuCu() {
        var staff = client().staff();
        String id = staffPatient(staff, "Lê Văn E", "0915000003");
        var old = staff.post("/api/staff/appointments", body("patientId", id, "serviceType", "CHAM_CUU", "slotDate", futureDate(2),
                "slotStart", "08:00")).expectStatus(201).json().get("appointment");
        var res = staff.post("/api/staff/appointments/" + old.get("id").stringValue() + "/reschedule",
                body("slotDate", futureDate(3), "slotStart", "13:00")).expectStatus(200).json();
        assertThat(res.get("appointment").get("rescheduledFromId").stringValue()).isEqualTo(old.get("id").stringValue());
        var prev = staff.get("/api/staff/appointments/" + old.get("id").stringValue()).expectStatus(200).json();
        assertThat(prev.get("appointment").get("status").stringValue()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("huỷ tại quầy phải có lý do (BR.26)")
    void huyTaiQuayCanLyDo() {
        var staff = client().staff();
        String id = staffPatient(staff, "Lê Văn F", "0915000004");
        var appt = staff.post("/api/staff/appointments", body("patientId", id, "serviceType", "CHAM_CUU", "slotDate", futureDate(2),
                "slotStart", "08:00")).expectStatus(201).json().get("appointment");
        String path = "/api/staff/appointments/" + appt.get("id").stringValue() + "/cancel";
        staff.post(path, body()).expectStatus(400);
        staff.post(path, body("reason", "Đặt nhầm")).expectStatus(200);
    }

    // ---------- Thêm bằng Java ----------

    @Test
    @DisplayName("[java] lịch gần nửa đêm không lệch ngày")
    void lichGanNuaDemKhongLechNgay() {
        // 00:30 giờ Việt Nam ngày D là 17:30 UTC ngày D-1: ngày khám phải là D, không phải D-1
        LocalDate day = LocalDate.of(2026, 11, 10);
        Instant midnightThirty = VnTime.slotInstant(day, "00:30");
        assertThat(midnightThirty.toString()).startsWith("2026-11-09T17:30");
        var staff = client().staff();
        clock.set(midnightThirty);
        businessClock.set(midnightThirty);

        String id = staffPatient(staff, "Ca Nửa Đêm", "0915000010");
        var appt = staff.post("/api/staff/appointments", body("patientId", id, "serviceType", "CHAM_CUU", "slotDate", "2026-11-10",
                "slotStart", "08:00")).expectStatus(201).json().get("appointment");
        assertThat(appt.get("slotDate").stringValue()).isEqualTo("2026-11-10");
        assertThat(appt.get("expectedTime").stringValue()).isEqualTo("2026-11-10T01:00:00.000Z");
        assertThat(jdbc.queryForObject("SELECT CAST(slot_date AS CHAR) FROM appointments", String.class)).isEqualTo("2026-11-10");
        // Ngày hôm trước theo giờ Việt Nam đã qua, dù theo UTC vẫn là hôm nay
        staff.post("/api/staff/appointments", body("patientId", id, "serviceType", "CHAM_CUU", "slotDate", "2026-11-09",
                "slotStart", "08:00")).expectStatus(400);
        var avail = staff.get("/api/staff/availability?date=2026-11-10").expectStatus(200).json();
        assertThat(avail.get("slots").get(0).get("available").booleanValue()).isTrue();
    }
}
