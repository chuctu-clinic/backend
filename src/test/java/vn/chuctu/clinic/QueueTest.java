package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.appointment.AppointmentService;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.queue.QueueService;
import vn.chuctu.clinic.queue.Settings;

/**
 * Cổng từ test/queue.test.js (check-in, hàng đợi, tự huỷ phiếu không đến), kèm vài test [java] cho điểm Node không có. 5 chỗ giờ là
 * chỗ điều trị: người vào chỗ là người đã được bác sĩ gọi khám và chỉ định điều trị. Quy trình bác sĩ và kỹ thuật viên chi tiết ở
 * VisitFlowTest.
 */
class QueueTest extends IntegrationTest {

    @Autowired AppointmentService appointments;
    @Autowired QueueService queue;
    @Autowired Settings settings;

    private int seq;

    private static LocalDate todayVn() {
        return VnTime.today(Instant.now());
    }

    private static Instant at(String hhmm) {
        return VnTime.slotInstant(todayVn(), hhmm);
    }

    /** Tạo phiếu hôm nay trực tiếp trong database, không phụ thuộc giờ chạy kiểm thử (todayAppointment của Node). */
    private long todayAppointment(String name) {
        return todayAppointment(name, "ONLINE", "08:00");
    }

    private long todayAppointment(String name, String source, String start) {
        return appointmentOn(todayVn(), name, source, start, null);
    }

    private long appointmentOn(LocalDate date, String name, String source, String start, Long userId) {
        seq++;
        Long staffId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'RECEPTIONIST'", Long.class);
        jdbc.update("INSERT INTO patients (patient_code, full_name, phone, created_at, updated_at) VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "CT-BN-T-" + seq, name, "09160000" + String.format("%02d", seq));
        Long patientId = jdbc.queryForObject("SELECT id FROM patients WHERE patient_code = ?", Long.class, "CT-BN-T-" + seq);
        return insertAppointment(patientId, date, source, start, userId, staffId);
    }

    private long insertAppointment(long patientId, LocalDate date, String source, String start, Long userId, Long staffId) {
        seq++;
        jdbc.update("INSERT INTO appointments (ticket_code, user_id, patient_id, service_type, slot_date, slot_start, expected_time, source, "
                + "created_by, created_at, updated_at) VALUES (?, ?, ?, 'CHAM_CUU', ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3))",
                "CT-PK-T-" + seq, userId, patientId, date, start, java.sql.Timestamp.from(VnTime.slotInstant(date, start)), source, staffId,
                java.sql.Timestamp.from(Instant.now().minusSeconds(3 * 86400)));
        return jdbc.queryForObject("SELECT id FROM appointments WHERE ticket_code = ?", Long.class, "CT-PK-T-" + seq);
    }

    private String apptStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id);
    }

    private int checkinCount(long appointmentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM checkins WHERE appointment_id = ?", Integer.class, appointmentId);
    }

    private String checkinStatus(long appointmentId) {
        return jdbc.queryForObject("SELECT status FROM checkins WHERE appointment_id = ? ORDER BY id DESC LIMIT 1", String.class, appointmentId);
    }

    private TestClient clinic;

    /** Quản trị viên mẫu: làm thay bác sĩ (gọi khám, chỉ định, kết luận) và kỹ thuật viên (chỗ, báo cáo). */
    private TestClient clinic() {
        if (clinic == null) clinic = clinicStaff();
        return clinic;
    }

    private void autoInvite(TestClient staff, boolean enabled) {
        clinic().put("/api/staff/queue/auto-invite", body("enabled", enabled)).expectStatus(200);
    }

    private long checkinIdOf(long appointmentId) {
        return jdbc.queryForObject("SELECT id FROM checkins WHERE appointment_id = ? ORDER BY id DESC LIMIT 1", Long.class, appointmentId);
    }

    /** Kỹ thuật viên lưu báo cáo điều trị cho người đang ở chỗ: chỗ trả lại, người đó chờ kết luận. */
    private void report(long appointmentId) {
        clinic().post("/api/staff/treatment/" + checkinIdOf(appointmentId) + "/report", body("other", "Đã điều trị")).expectStatus(200);
    }

    private JsonNode queueState(TestClient staff) {
        return staff.get("/api/staff/queue").expectStatus(200).json();
    }

    /** n người check-in, được bác sĩ gọi khám và chỉ định điều trị (tự động mời đang bật thì vào chỗ ngay), theo thứ tự. */
    private List<Long> checkInAll(TestClient staff, int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(todayAppointment("Người " + i));
        for (long id : ids) {
            staff.post("/api/staff/appointments/" + id + "/checkin", body()).expectStatus(200);
            callIn(clinic(), id);
            orderTreatment(clinic(), id);
        }
        return ids;
    }

    // ---------- Giờ nhận bệnh 8:00 – 16:30 ----------

    @Test
    @DisplayName("chỉ trong giờ nhận bệnh mới là giờ check-in")
    void chiTrongGioNhanBenh() {
        assertThat(QueueService.isReceivingTime(at("07:59"))).isFalse();
        assertThat(QueueService.isReceivingTime(at("08:00"))).isTrue();
        assertThat(QueueService.isReceivingTime(at("16:29"))).isTrue();
        assertThat(QueueService.isReceivingTime(at("16:30"))).isFalse();
        assertThat(QueueService.isReceivingTime(at("22:00"))).isFalse();
    }

    @Test
    @DisplayName("ngoài giờ nhận bệnh không check-in được, phiếu vẫn Chưa đến")
    void ngoaiGioNhanBenh() {
        var staff = client().staff();
        long a = todayAppointment("Ngoài Giờ");
        for (String hhmm : List.of("22:00", "07:30", "16:30")) {
            businessClock.set(at(hhmm));
            var res = staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(409).json();
            assertThat(res.get("error").get("code").stringValue()).isEqualTo("CLOSED");
            assertThat(res.get("error").get("message").stringValue()).contains("8:00 – 16:30");
        }
        assertThat(apptStatus(a)).isEqualTo("BOOKED");
        assertThat(checkinCount(a)).isZero();

        businessClock.set(at("16:29"));
        staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(200);
    }

    // ---------- Tự huỷ phiếu không đến sau 17:00 ----------

    @Test
    @DisplayName("trước 17:00 chưa huỷ, từ 17:00 phiếu hôm nay còn Chưa đến chuyển sang Đã huỷ")
    void truoc17ChuaHuy() {
        long a = todayAppointment("Không Đến", "ONLINE", "09:00");
        assertThat(appointments.autoCancelNoShows(at("16:59"))).isZero();
        assertThat(apptStatus(a)).isEqualTo("BOOKED");

        assertThat(appointments.autoCancelNoShows(at("17:00"))).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT status, cancel_reason, cancelled_at, cancelled_by FROM appointments WHERE id = ?", a);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat((String) row.get("cancel_reason")).contains("Không đến khám");
        assertThat(row.get("cancelled_at")).isNotNull();
        assertThat(row.get("cancelled_by")).isNull();
    }

    @Test
    @DisplayName("chỉ huỷ phiếu Chưa đến của hôm nay và các ngày trước, không đụng phiếu đã check-in hay ngày sau")
    void chiHuyPhieuChuaDen() {
        var staff = client().staff();
        long checkedIn = todayAppointment("Đã Đến");
        staff.post("/api/staff/appointments/" + checkedIn + "/checkin", body()).expectStatus(200);
        long yesterday = todayAppointment("Hôm Qua");
        jdbc.update("UPDATE appointments SET slot_date = ? WHERE id = ?", todayVn().minusDays(1), yesterday);
        long tomorrow = todayAppointment("Ngày Mai");
        jdbc.update("UPDATE appointments SET slot_date = ? WHERE id = ?", todayVn().plusDays(1), tomorrow);

        // Phiếu ngày trước bị bỏ sót (server tắt lúc 17:00) được huỷ bù, kể cả buổi sáng
        assertThat(appointments.autoCancelNoShows(at("09:00"))).isEqualTo(1);
        assertThat(apptStatus(yesterday)).isEqualTo("CANCELLED");

        appointments.autoCancelNoShows(at("17:30"));
        // Đã check-in (tự động mời có thể đưa luôn vào khám) thì không bị huỷ
        assertThat(apptStatus(checkedIn)).isNotEqualTo("CANCELLED");
        assertThat(apptStatus(tomorrow)).isEqualTo("BOOKED");
    }

    // ---------- Nhóm ưu tiên (BR.28) ----------

    private static String group(String source, String createdAt, String checkIn) {
        return QueueService.priorityGroupFor(LocalDate.parse("2026-10-20"), "09:00", source, vn(createdAt), vn(checkIn));
    }

    /** new Date('2026-10-20THH:mm:00+07:00') của Node. */
    private static Instant vn(String hhmm) {
        return java.time.OffsetDateTime.parse("2026-10-20T" + hhmm + ":00+07:00").toInstant();
    }

    @Test
    @DisplayName("phiếu đặt trước, đến trong khung giờ thì ưu tiên")
    void datTruocDenTrongKhung() {
        assertThat(group("ONLINE", "07:00", "09:20")).isEqualTo("PRIORITY");
        assertThat(group("ONLINE", "07:00", "08:50")).isEqualTo("PRIORITY");
    }

    @Test
    @DisplayName("đến sau khung giờ hoặc quá sớm thì xếp nhóm thường")
    void denSauHoacQuaSom() {
        assertThat(group("ONLINE", "07:00", "10:05")).isEqualTo("NORMAL");
        assertThat(group("ONLINE", "07:00", "08:00")).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("khách đến trực tiếp, đặt hộ trong ngày, xếp nhóm thường")
    void khachDenTrucTiep() {
        assertThat(group("COUNTER", "09:10", "09:15")).isEqualTo("NORMAL");
    }

    // ---------- Check-in và điều phối hàng đợi ----------

    @Test
    @DisplayName("check-in rồi hoàn tác đưa phiếu về Chưa đến (BR.27)")
    void checkInRoiHoanTac() {
        var staff = client().staff();
        autoInvite(staff, false);
        long a = todayAppointment("Bệnh nhân A");
        var res = staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(200).json();
        assertThat(res.get("appointment").get("status").stringValue()).isEqualTo("CHECKED_IN");
        var undo = staff.post("/api/staff/appointments/" + a + "/undo-checkin", body()).expectStatus(200).json();
        assertThat(undo.get("appointment").get("status").stringValue()).isEqualTo("BOOKED");
        assertThat(queueState(staff).get("waitingCount").intValue()).isZero();
    }

    @Test
    @DisplayName("phiếu đã huỷ không check-in được (BR.24)")
    void phieuDaHuyKhongCheckIn() {
        var staff = client().staff();
        long a = todayAppointment("Bệnh nhân B");
        staff.post("/api/staff/appointments/" + a + "/cancel", body("reason", "Không đến được")).expectStatus(200);
        staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(409);
    }

    @Test
    @DisplayName("tối đa 5 người ở chỗ điều trị, tự động mời người kế tiếp khi còn chỗ (BR.29)")
    void toiDa5NguoiTrongPhong() {
        var staff = client().staff();
        checkInAll(staff, 7);

        var q = queueState(staff);
        assertThat(q.get("inRoomCount").intValue()).isEqualTo(5);
        assertThat(q.get("treatmentWaitingCount").intValue()).isEqualTo(2);

        // Người ở chỗ 1 điều trị xong: chỗ trả lại, người kế tiếp vào; bác sĩ kết luận thì hoàn thành (BR.30)
        long firstAppt = Long.parseLong(q.get("seats").get(0).get("appointmentId").stringValue());
        report(firstAppt);
        q = queueState(staff);
        assertThat(q.get("inRoomCount").intValue()).isEqualTo(5);
        assertThat(q.get("treatmentWaitingCount").intValue()).isEqualTo(1);
        assertThat(q.get("awaitingConclusion").size()).isEqualTo(1);
        clinic().post("/api/staff/appointments/" + firstAppt + "/record",
                body("diagnosis", "Đau vai gáy", "pulse", "78", "bloodPressure", "120/80", "doctorName", "BS. Minh")).expectStatus(201);
        q = queueState(staff);
        assertThat(q.get("completedCount").intValue()).isEqualTo(1);
        assertThat(q.get("awaitingConclusion").size()).isZero();
        var done = staff.get("/api/staff/appointments/" + firstAppt).expectStatus(200).json();
        assertThat(done.get("appointment").get("status").stringValue()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("[java] tối đa 5 người ở chỗ điều trị, báo cáo xong một người thì người kế tiếp vào chỗ đó (BR.29, BR.30)")
    void javaToiDa5NguoiTrongPhong() {
        var staff = client().staff();
        List<Long> ids = checkInAll(staff, 7);

        var q = queueState(staff);
        assertThat(q.get("inRoomCount").intValue()).isEqualTo(5);
        assertThat(q.get("treatmentWaitingCount").intValue()).isEqualTo(2);
        assertThat(q.get("capacity").intValue()).isEqualTo(5);
        assertThat(q.get("autoInvite").booleanValue()).isTrue();

        JsonNode first = q.get("seats").get(0);
        assertThat(first.get("seatNo").intValue()).isEqualTo(1);
        long checkinId = Long.parseLong(first.get("id").stringValue());
        long firstAppt = Long.parseLong(first.get("appointmentId").stringValue());
        report(firstAppt);

        q = queueState(staff);
        assertThat(q.get("inRoomCount").intValue()).isEqualTo(5);
        assertThat(q.get("treatmentWaitingCount").intValue()).isEqualTo(1);
        // Người kế tiếp (thứ 6) vào đúng chỗ vừa trống
        assertThat(q.get("seats").get(0).get("appointmentId").stringValue()).isEqualTo(String.valueOf(ids.get(5)));
        assertThat(checkinStatus(firstAppt)).isEqualTo("WAITING_CONCLUSION");
        assertThat(jdbc.queryForObject("SELECT seat_no FROM checkins WHERE id = ?", Integer.class, checkinId)).isNull();
    }

    @Test
    @DisplayName("nhóm ưu tiên đứng trên, trong nhóm ai check-in trước đứng trước")
    void nhomUuTienDungTren() {
        var staff = client().staff();
        autoInvite(staff, false);
        long walkIn = todayAppointment("Khách vãng lai", "COUNTER", "08:00");
        jdbc.update("UPDATE appointments SET created_at = UTC_TIMESTAMP(3) WHERE id = ?", walkIn);
        long booked = todayAppointment("Có phiếu đặt trước");
        staff.post("/api/staff/appointments/" + walkIn + "/checkin", body()).expectStatus(200);
        staff.post("/api/staff/appointments/" + booked + "/checkin", body()).expectStatus(200);
        // Nhóm của người có phiếu phụ thuộc giờ chạy kiểm thử, nên đặt cố định là đến đúng khung giờ
        jdbc.update("UPDATE checkins SET priority_group = 'PRIORITY' WHERE appointment_id = ?", booked);
        var waiting = queueState(staff).get("waiting");
        assertThat(waiting.get(0).get("patient").get("fullName").stringValue()).isEqualTo("Có phiếu đặt trước");
        JsonNode last = waiting.get(waiting.size() - 1);
        assertThat(last.get("patient").get("fullName").stringValue()).isEqualTo("Khách vãng lai");
        assertThat(last.get("priorityGroup").stringValue()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("hoàn tác vào chỗ đưa người đó về hàng chờ điều trị, chỗ trống nhận người kế tiếp (BR.27)")
    void hoanTacVaoKham() {
        var staff = client().staff();
        checkInAll(staff, 6);
        var q = queueState(staff);
        String seat1 = q.get("seats").get(0).get("id").stringValue();
        q = clinic().post("/api/staff/treatment/" + seat1 + "/undo-seat", body()).expectStatus(200).json();
        assertThat(q.get("inRoomCount").intValue()).isEqualTo(5);
        assertThat(q.get("treatmentWaitingCount").intValue()).isEqualTo(1);
        assertThat(q.get("treatmentWaiting").get(0).get("id").stringValue()).isEqualTo(seat1);
        assertThat(q.get("seats").get(0).get("id").stringValue()).isNotEqualTo(seat1);
    }

    @Test
    @DisplayName("sửa phiếu kết quả ghi lại lịch sử sửa (UC.9.9)")
    void suaPhieuKetQua() {
        var staff = clinicStaff();
        long a = todayAppointment("Người sửa phiếu");
        staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(200);
        callIn(staff, a);
        var created = staff.post("/api/staff/appointments/" + a + "/record", body("diagnosis", "Đau lưng")).expectStatus(201).json();
        var updated = staff.put("/api/staff/records/" + created.get("record").get("id").stringValue(),
                body("diagnosis", "Thoát vị đĩa đệm nhẹ")).expectStatus(200).json();
        var history = updated.get("record").get("editHistory");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("changes").get("diagnosis").get("from").stringValue()).isEqualTo("Đau lưng");
        assertThat(history.get(0).get("changes").get("diagnosis").get("to").stringValue()).isEqualTo("Thoát vị đĩa đệm nhẹ");
    }

    @Test
    @DisplayName("hồ sơ bị khoá không check-in và không sửa phiếu kết quả được (BR.38)")
    void hoSoBiKhoa() {
        var staff = client().staff();
        var admin = client().staff("admin@chuctu.vn");
        long a = todayAppointment("Người bị khoá");
        long patientId = jdbc.queryForObject("SELECT patient_id FROM appointments WHERE id = ?", Long.class, a);
        admin.post("/api/admin/patients/" + patientId + "/lock", body()).expectStatus(200);
        staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(403);
        staff.patch("/api/staff/patients/" + patientId, body("fullName", "Tên mới")).expectStatus(403);
        var detail = staff.get("/api/staff/patients/" + patientId).expectStatus(200).json();
        assertThat(detail.get("patient").get("isLocked").booleanValue()).isTrue();
    }

    @Test
    @DisplayName("mời đúng một người vào chỗ điều trị và hoàn tác; Lịch trong ngày hiện bước của lượt khám")
    void moiDungMotNguoiTuLich() {
        var staff = client().staff();
        autoInvite(staff, false);
        List<Long> ids = checkInAll(staff, 2);
        long first = ids.get(0);
        long second = ids.get(1);

        clinic().post("/api/staff/treatment/" + checkinIdOf(second) + "/seat", body()).expectStatus(200);
        assertThat(apptStatus(second)).isEqualTo("IN_PROGRESS");
        var day = staff.get("/api/staff/appointments").expectStatus(200).json();
        JsonNode row = item(day, second);
        assertThat(row.get("checkin").get("status").stringValue()).isEqualTo("IN_ROOM");
        assertThat(row.get("checkin").get("enteredAt").stringValue()).isNotEmpty();
        assertThat(item(day, first).get("checkin").get("status").stringValue()).isEqualTo("WAITING_TREATMENT");

        var state = queueState(staff);
        assertThat(state.get("inRoomCount").intValue()).isEqualTo(1);
        assertThat(state.get("treatmentWaitingCount").intValue()).isEqualTo(1);

        clinic().post("/api/staff/treatment/" + checkinIdOf(second) + "/undo-seat", body()).expectStatus(200);
        assertThat(checkinStatus(second)).isEqualTo("WAITING_TREATMENT");
        assertThat(apptStatus(second)).isEqualTo("IN_PROGRESS");
        clinic().post("/api/staff/treatment/" + checkinIdOf(second) + "/undo-seat", body()).expectStatus(409);
    }

    private static JsonNode item(JsonNode day, long appointmentId) {
        for (JsonNode x : day.get("items")) if (x.get("id").stringValue().equals(String.valueOf(appointmentId))) return x;
        throw new AssertionError("Không có phiếu " + appointmentId + " trong lịch");
    }

    @Test
    @DisplayName("phiếu kết quả chỉ sửa được trong 24 giờ sau khi xác nhận")
    void phieuKetQuaChiSuaTrong24Gio() {
        var staff = clinicStaff();
        long a = todayAppointment("Người quá hạn sửa");
        staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(200);
        callIn(staff, a);
        var created = staff.post("/api/staff/appointments/" + a + "/record", body("diagnosis", "Đau gối")).expectStatus(201).json();
        String id = created.get("record").get("id").stringValue();
        assertThat(created.get("record").get("editableUntil").stringValue()).isNotEmpty();
        jdbc.update("UPDATE medical_records SET created_at = ? WHERE id = ?",
                java.time.LocalDateTime.ofInstant(Instant.now().minusSeconds(25 * 3600), java.time.ZoneOffset.UTC), Long.parseLong(id));
        staff.put("/api/staff/records/" + id, body("diagnosis", "Thoái hoá khớp gối")).expectStatus(403);

        var list = staff.get("/api/staff/patients?q=" + java.net.URLEncoder.encode("Người quá hạn sửa", java.nio.charset.StandardCharsets.UTF_8))
                .expectStatus(200).json();
        assertThat(list.get("items").get(0).get("lastVisitDate").stringValue()).isEqualTo(todayVn().toString());
        var dates = staff.get("/api/staff/appointments/dates?month=" + todayVn().toString().substring(0, 7)).expectStatus(200).json();
        assertThat(dates.get("dates").toString()).contains(todayVn().toString());
    }

    // ---------- Phiếu đã xong không bao giờ được xếp chỗ lại ----------

    /** Chèn thẳng một check-in; chỉ định từ một giờ trước để đứng đầu hàng chờ điều trị. */
    private void insertCheckin(long appointmentId, String status, Integer seat) {
        long patientId = jdbc.queryForObject("SELECT patient_id FROM appointments WHERE id = ?", Long.class, appointmentId);
        jdbc.update("INSERT INTO checkins (appointment_id, patient_id, visit_date, check_in_time, priority_group, status, seat_no, treatment_kinds, "
                + "ordered_at, created_at) VALUES (?, ?, ?, UTC_TIMESTAMP(3), 'NORMAL', ?, ?, 'CHAM_CUU', "
                + "TIMESTAMPADD(HOUR, -1, UTC_TIMESTAMP(3)), UTC_TIMESTAMP(3))", appointmentId, patientId, todayVn(), status, seat);
    }

    @Test
    @DisplayName("[java] phiếu đã hoàn thành mà check-in còn chờ điều trị thì không bao giờ được mời vào chỗ")
    void javaPhieuDaXongKhongDuocMoi() {
        long a = todayAppointment("Người đã xong");
        jdbc.update("UPDATE appointments SET status = 'COMPLETED' WHERE id = ?", a);
        insertCheckin(a, "WAITING_TREATMENT", null);

        clinic().post("/api/staff/treatment/" + checkinIdOf(a) + "/seat", body()).expectStatus(409);
        queue.fillSeats(todayVn());
        assertThat(apptStatus(a)).isEqualTo("COMPLETED");
        assertThat(checkinStatus(a)).isEqualTo("WAITING_TREATMENT");
        assertThat(jdbc.queryForObject("SELECT seat_no FROM checkins WHERE appointment_id = ?", Integer.class, a)).isNull();
        // Dòng hỏng không phải là hết chỗ: không còn ai mời được thì báo hàng chờ trống
        var res = clinic().post("/api/staff/treatment/invite", body()).expectStatus(409).json();
        assertThat(res.get("error").get("code").stringValue()).isEqualTo("QUEUE_EMPTY");
        assertThat(apptStatus(a)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("[java] mời kế tiếp bỏ qua người đầu hàng chờ điều trị không xếp chỗ được và mời người sau")
    void javaMoiBoQuaDongHong() {
        var staff = client().staff();
        autoInvite(staff, false);
        long stuck = todayAppointment("Người đã xong");
        jdbc.update("UPDATE appointments SET status = 'COMPLETED' WHERE id = ?", stuck);
        insertCheckin(stuck, "WAITING_TREATMENT", null);
        long next = checkInAll(staff, 1).get(0);
        // Người hỏng đứng đầu hàng (chỉ định sớm hơn), người chờ thật đứng sau
        assertThat(queueState(staff).get("treatmentWaiting").get(0).get("appointmentId").stringValue()).isEqualTo(String.valueOf(stuck));

        clinic().post("/api/staff/treatment/invite", body()).expectStatus(200);
        assertThat(apptStatus(next)).isEqualTo("IN_PROGRESS");
        assertThat(checkinStatus(next)).isEqualTo("IN_ROOM");
        assertThat(apptStatus(stuck)).isEqualTo("COMPLETED");
        assertThat(checkinStatus(stuck)).isEqualTo("WAITING_TREATMENT");
    }

    @Test
    @DisplayName("[java] phiếu đã hoàn thành mà check-in còn ở chỗ thì hoàn tác vào chỗ bị từ chối, không đổi gì")
    void javaPhieuDaXongKhongHoanTacDuoc() {
        long a = todayAppointment("Người đã xong trong phòng");
        jdbc.update("UPDATE appointments SET status = 'COMPLETED' WHERE id = ?", a);
        insertCheckin(a, "IN_ROOM", 1);

        var res = clinic().post("/api/staff/treatment/" + checkinIdOf(a) + "/undo-seat", body()).expectStatus(409).json();
        assertThat(res.get("error").get("code").stringValue()).isEqualTo("STALE");
        assertThat(apptStatus(a)).isEqualTo("COMPLETED");
        assertThat(checkinStatus(a)).isEqualTo("IN_ROOM");
        assertThat(jdbc.queryForObject("SELECT seat_no FROM checkins WHERE appointment_id = ?", Integer.class, a)).isEqualTo(1);
    }

    // ---------- Mời vào khám (UC.10.2) ----------

    @Test
    @DisplayName("hàng chờ trống thì báo chưa có ai, ở cả hàng chờ khám lẫn hàng chờ điều trị")
    void hangChoTrong() {
        var res = clinic().post("/api/staff/treatment/invite", body()).expectStatus(409).json();
        assertThat(res.get("error").get("code").stringValue()).isEqualTo("QUEUE_EMPTY");
        res = clinic().post("/api/staff/exam/call-next", body()).expectStatus(409).json();
        assertThat(res.get("error").get("code").stringValue()).isEqualTo("QUEUE_EMPTY");
    }

    @Test
    @DisplayName("đã đủ 5 chỗ điều trị thì người tiếp theo vẫn chờ")
    void phongDay() {
        var staff = client().staff();
        checkInAll(staff, 6);
        var res = clinic().post("/api/staff/treatment/invite", body()).expectStatus(409).json();
        assertThat(res.get("error").get("code").stringValue()).isEqualTo("ROOM_FULL");
        var state = queueState(staff);
        assertThat(state.get("inRoomCount").intValue()).isEqualTo(5);
        assertThat(state.get("treatmentWaitingCount").intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("còn chỗ thì mời đúng người đầu hàng chờ điều trị")
    void conGheMoiNguoiDau() {
        var staff = client().staff();
        autoInvite(staff, false);
        List<Long> ids = checkInAll(staff, 2);

        var state = clinic().post("/api/staff/treatment/invite", body()).expectStatus(200).json();
        assertThat(state.get("inRoomCount").intValue()).isEqualTo(1);
        assertThat(checkinStatus(ids.get(0))).isEqualTo("IN_ROOM");
        assertThat(checkinStatus(ids.get(1))).isEqualTo("WAITING_TREATMENT");
    }

    // ---------- Chỉ có ở bản Java ----------

    @Test
    @DisplayName("[java] người dùng chỉ thấy Đã đặt lịch khi phiếu ở trạng thái đã check-in hoặc đang khám")
    void javaNguoiDungThayDaDatLich() {
        assertThat(AppointmentService.userStatus("CHECKED_IN")).isEqualTo("BOOKED");
        assertThat(AppointmentService.userStatus("IN_PROGRESS")).isEqualTo("BOOKED");

        var user = client().registerUser("0919000011", "Người Đi Khám");
        var staff = client().staff();
        autoInvite(staff, false);
        long patientId = Long.parseLong(user.selfPatientId());
        Long userId = jdbc.queryForObject("SELECT user_id FROM patients WHERE id = ?", Long.class, patientId);
        Long staffId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'RECEPTIONIST'", Long.class);
        long a = insertAppointment(patientId, todayVn(), "ONLINE", "08:00", userId, staffId);

        staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(200);
        assertThat(apptStatus(a)).isEqualTo("CHECKED_IN");
        assertThat(userStatusOf(user, a)).isEqualTo("BOOKED");

        callIn(clinic(), a);
        assertThat(apptStatus(a)).isEqualTo("IN_PROGRESS");
        assertThat(userStatusOf(user, a)).isEqualTo("BOOKED");
        assertThat(user.get("/api/me/appointments/" + a).expectStatus(200).json().get("appointment").get("status").stringValue()).isEqualTo("BOOKED");
    }

    private static String userStatusOf(TestClient user, long appointmentId) {
        for (JsonNode x : user.get("/api/me/appointments").expectStatus(200).json().get("items")) {
            if (x.get("id").stringValue().equals(String.valueOf(appointmentId))) return x.get("status").stringValue();
        }
        throw new AssertionError("Người dùng không thấy phiếu " + appointmentId);
    }

    @Test
    @DisplayName("[java] huỷ phiếu đang điều trị thì rời hàng đợi và nhả chỗ")
    void javaHuyPhieuDaCheckInNhaGhe() {
        var staff = client().staff();
        List<Long> ids = checkInAll(staff, 6);
        assertThat(queueState(staff).get("treatmentWaitingCount").intValue()).isEqualTo(1);

        // Người ở chỗ 1 huỷ phiếu: check-in về REVERTED, chỗ nhả ra và (tự động mời đang bật) người đang chờ vào chỗ ngay.
        // Khác Node: Node để chỗ trống đến lần thay đổi hàng đợi sau.
        staff.post("/api/staff/appointments/" + ids.get(0) + "/cancel", body("reason", "Bệnh nhân xin huỷ")).expectStatus(200);
        assertThat(checkinStatus(ids.get(0))).isEqualTo("REVERTED");
        var q = queueState(staff);
        assertThat(q.get("inRoomCount").intValue()).isEqualTo(5);
        assertThat(q.get("seats").get(0).get("appointmentId").stringValue()).isEqualTo(String.valueOf(ids.get(5)));
        assertThat(q.get("treatmentWaitingCount").intValue()).isZero();

        // Người vừa vào chỗ huỷ phiếu: rời chỗ, không còn ai chờ nên chỗ trống
        staff.post("/api/staff/appointments/" + ids.get(5) + "/cancel", body("reason", "Đặt nhầm")).expectStatus(200);
        q = queueState(staff);
        assertThat(q.get("inRoomCount").intValue()).isEqualTo(4);
        assertThat(q.get("seats").get(0).get("empty").booleanValue()).isTrue();

        // Chỗ vừa nhả dùng lại được
        long late = todayAppointment("Người đến muộn");
        staff.post("/api/staff/appointments/" + late + "/checkin", body()).expectStatus(200);
        callIn(clinic(), late);
        orderTreatment(clinic(), late);
        q = queueState(staff);
        assertThat(q.get("inRoomCount").intValue()).isEqualTo(5);
        assertThat(q.get("seats").get(0).get("appointmentId").stringValue()).isEqualTo(String.valueOf(late));
    }

    @Test
    @DisplayName("[java] check-in lỗi trùng khoá thì phiếu về lại Chưa đến, không để lại dòng check-in")
    void javaCheckInLoiTrungKhoa() {
        var staff = client().staff();
        long a = todayAppointment("Trùng khoá");
        // Trạng thái không hợp lệ: đã có check-in hiệu lực nhưng phiếu vẫn Chưa đến
        jdbc.update("INSERT INTO checkins (appointment_id, patient_id, visit_date, check_in_time, priority_group, status, created_at) "
                + "SELECT id, patient_id, slot_date, UTC_TIMESTAMP(3), 'NORMAL', 'COMPLETED', UTC_TIMESTAMP(3) FROM appointments WHERE id = ?", a);
        var res = staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(409).json();
        assertThat(res.get("error").get("code").stringValue()).isEqualTo("DUPLICATE");
        assertThat(apptStatus(a)).isEqualTo("BOOKED");
        assertThat(checkinCount(a)).isEqualTo(1);
    }

    @Test
    @DisplayName("[java] check-in hai lần cùng một phiếu: lần hai báo đã check-in")
    void javaCheckInHaiLan() {
        var staff = client().staff();
        autoInvite(staff, false);
        long a = todayAppointment("Hai lần");
        staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(200);
        var res = staff.post("/api/staff/appointments/" + a + "/checkin", body()).expectStatus(409).json();
        assertThat(res.get("error").get("code").stringValue()).isEqualTo("ALREADY_CHECKED_IN");
        assertThat(checkinCount(a)).isEqualTo(1);
    }

    @Test
    @DisplayName("[java] tự động mời mặc định bật, tắt rồi bật lại thì lấp chỗ điều trị trống; tiếp tân không đổi được")
    void javaTuDongMoi() {
        var staff = client().staff();
        assertThat(settings.get("queue.autoInvite", true, Boolean.class)).isTrue();
        staff.put("/api/staff/queue/auto-invite", body("enabled", false)).expectStatus(403);
        autoInvite(staff, false);
        assertThat(queueState(staff).get("autoInvite").booleanValue()).isFalse();
        assertThat(settings.get("queue.autoInvite", true, Boolean.class)).isFalse();

        checkInAll(staff, 2);
        var q = queueState(staff);
        assertThat(q.get("inRoomCount").intValue()).isZero();
        assertThat(q.get("treatmentWaitingCount").intValue()).isEqualTo(2);

        var after = clinic().put("/api/staff/queue/auto-invite", body("enabled", true)).expectStatus(200).json();
        assertThat(after.get("autoInvite").booleanValue()).isTrue();
        assertThat(after.get("inRoomCount").intValue()).isEqualTo(2);
        assertThat(after.get("treatmentWaitingCount").intValue()).isZero();

        var bad = clinic().put("/api/staff/queue/auto-invite", body()).expectStatus(400).json();
        assertThat(bad.get("error").get("code").stringValue()).isEqualTo("VALIDATION");
        assertThat(bad.get("error").get("fields").get("enabled").stringValue()).isEqualTo("Required");
        clinic().put("/api/staff/queue/auto-invite", body("enabled", "yes")).expectStatus(400);
    }

    @Test
    @DisplayName("[java] đặt hộ kèm check-in ngay: vào hàng đợi; ngoài giờ nhận bệnh thì không tạo phiếu")
    void javaDatHoKemCheckIn() {
        var staff = client().staff();
        autoInvite(staff, false);
        clock.set(at("10:00"));
        long patientId = Long.parseLong(staff.post("/api/staff/patients", body("fullName", "Khách tại quầy", "birthYear", 1960, "phone", "0915555001"))
                .expectStatus(201).json().get("patient").get("id").stringValue());
        var created = staff.post("/api/staff/appointments", body("patientId", String.valueOf(patientId), "serviceType", "CHAM_CUU",
                "slotDate", todayVn().toString(), "slotStart", "11:00", "checkInNow", true)).expectStatus(201).json();
        assertThat(created.get("appointment").get("status").stringValue()).isEqualTo("CHECKED_IN");
        assertThat(queueState(staff).get("waitingCount").intValue()).isEqualTo(1);

        long other = Long.parseLong(staff.post("/api/staff/patients", body("fullName", "Khách khác", "birthYear", 1961, "phone", "0915555002"))
                .expectStatus(201).json().get("patient").get("id").stringValue());
        businessClock.set(at("16:45"));
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM appointments", Integer.class);
        var closed = staff.post("/api/staff/appointments", body("patientId", String.valueOf(other), "serviceType", "CHAM_CUU",
                "slotDate", todayVn().toString(), "slotStart", "15:00", "checkInNow", true)).expectStatus(409).json();
        assertThat(closed.get("error").get("code").stringValue()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments", Integer.class)).isEqualTo(before);
    }
}
