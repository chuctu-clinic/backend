package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.common.VnTime;

/** Cổng từ test/medical-records.test.js (xem kết quả khám, lập và sửa phiếu kết quả), kèm các test [java] cho điểm Node không có. */
class MedicalRecordsTest extends IntegrationTest {

    private int seq;

    private static String futureDate(int days) {
        return VnTime.today(Instant.now()).plusDays(days).toString();
    }

    /** Tạo phiếu hôm nay của hồ sơ thuộc tài khoản, không phụ thuộc giờ chạy kiểm thử. */
    private long todayAppointment(String patientId) {
        seq++;
        long patient = Long.parseLong(patientId);
        Long staffId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'RECEPTIONIST'", Long.class);
        Long userId = jdbc.queryForObject("SELECT user_id FROM patients WHERE id = ?", Long.class, patient);
        LocalDate date = VnTime.today(Instant.now());
        jdbc.update("INSERT INTO appointments (ticket_code, user_id, patient_id, service_type, slot_date, slot_start, expected_time, source, "
                + "created_by, created_at, updated_at) VALUES (?, ?, ?, 'CHAM_CUU', ?, '08:00', ?, 'ONLINE', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "CT-PK-R-" + seq, userId, patient, date, java.sql.Timestamp.from(VnTime.slotInstant(date, "08:00")), staffId);
        return jdbc.queryForObject("SELECT id FROM appointments WHERE ticket_code = ?", Long.class, "CT-PK-R-" + seq);
    }

    private record Visit(long appointmentId, JsonNode record) {}

    /** Đưa người bệnh vào phòng khám rồi lập phiếu kết quả. */
    private Visit completedVisit(TestClient staff, String patientId, Map<String, Object> data) {
        long appt = todayAppointment(patientId);
        staff.post("/api/staff/appointments/" + appt + "/checkin", body()).expectStatus(200);
        callIn(staff, appt);
        var res = staff.post("/api/staff/appointments/" + appt + "/record", data).expectStatus(201);
        return new Visit(appt, res.json().get("record"));
    }

    private Visit completedVisit(TestClient staff, String patientId) {
        return completedVisit(staff, patientId, body("diagnosis", "Đau lưng", "doctorName", "BS An", "advice", "Tập vươn vai mỗi sáng",
                "treatmentNotes", body("physio", "Chiếu đèn hồng ngoại")));
    }

    // ---------- Xem kết quả khám (BR.20, BR.21) ----------

    @Test
    @DisplayName("kết quả chỉ hiện sau khi phòng khám đã ghi nhận")
    void ketQuaChiHienSauKhiGhiNhan() {
        var user = client().registerUser();
        long appt = todayAppointment(user.selfPatientId());
        user.get("/api/me/appointments/" + appt + "/result").expectStatus(404);
        assertThat(user.get("/api/me/history").expectStatus(200).json().get("items")).isEmpty();
    }

    @Test
    @DisplayName("chủ tài khoản xem được kết quả, không thấy người ghi và lịch sử sửa nội bộ")
    void chuTaiKhoanXemKetQua() {
        var staff = clinicStaff();
        var user = client().registerUser();
        Visit visit = completedVisit(staff, user.selfPatientId());

        JsonNode record = user.get("/api/me/appointments/" + visit.appointmentId() + "/result").expectStatus(200).json().get("record");
        assertThat(record.get("diagnosis").stringValue()).isEqualTo("Đau lưng");
        assertThat(record.get("advice").stringValue()).isEqualTo("Tập vươn vai mỗi sáng");
        assertThat(record.get("treatmentNotes").get("physio").stringValue()).isEqualTo("Chiếu đèn hồng ngoại");
        assertThat(record.has("editHistory")).isFalse();
        assertThat(record.has("recordedByName")).isFalse();

        JsonNode item = user.get("/api/me/history").expectStatus(200).json().get("items").get(0);
        assertThat(item.get("diagnosis").stringValue()).isEqualTo("Đau lưng");
        assertThat(item.has("editHistory")).isFalse();
        assertThat(item.has("recordedByName")).isFalse();
    }

    @Test
    @DisplayName("tài khoản khác không xem được kết quả và lịch sử khám của người này")
    void taiKhoanKhacKhongXemDuoc() {
        var staff = clinicStaff();
        var owner = client().registerUser("0917000001", "Chủ Hồ Sơ");
        var other = client().registerUser("0917000002", "Người Khác");
        String patientId = owner.selfPatientId();
        Visit visit = completedVisit(staff, patientId);

        other.get("/api/me/appointments/" + visit.appointmentId() + "/result").expectStatus(404);
        assertThat(other.get("/api/me/history?patientId=" + patientId).expectStatus(200).json().get("items")).isEmpty();
        assertThat(other.get("/api/me/history").expectStatus(200).json().get("items")).isEmpty();
    }

    @Test
    @DisplayName("[java] người dùng không xem được kết quả của tài khoản khác")
    void javaNguoiDungKhongXemKetQuaTaiKhoanKhac() {
        var staff = clinicStaff();
        var a = client().registerUser("0917000011", "Người A");
        var b = client().registerUser("0917000012", "Người B");
        Visit visitOfB = completedVisit(staff, b.selfPatientId(), body("diagnosis", "Bí mật của B"));

        var res = a.get("/api/me/appointments/" + visitOfB.appointmentId() + "/result").expectStatus(404);
        assertThat(res.text()).doesNotContain("Bí mật của B");
        // B vẫn xem được kết quả của chính mình
        assertThat(b.get("/api/me/appointments/" + visitOfB.appointmentId() + "/result").expectStatus(200).json().get("record")
                .get("diagnosis").stringValue()).isEqualTo("Bí mật của B");
    }

    @Test
    @DisplayName("lọc lịch sử theo từng hồ sơ trong tài khoản")
    void locLichSuTheoHoSo() {
        var staff = clinicStaff();
        var user = client().registerUser();
        String selfId = user.selfPatientId();
        String relative = user.post("/api/me/patients",
                body("fullName", "Trần Thị Mẹ", "birthYear", 1940, "gender", "NU", "relationship", "KHAC")).expectStatus(201)
                .json().get("patient").get("id").stringValue();
        completedVisit(staff, selfId, body("diagnosis", "Của mình"));
        completedVisit(staff, relative, body("diagnosis", "Của mẹ"));

        assertThat(user.get("/api/me/history").expectStatus(200).json().get("items")).hasSize(2);
        JsonNode filtered = user.get("/api/me/history?patientId=" + relative).expectStatus(200).json().get("items");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("diagnosis").stringValue()).isEqualTo("Của mẹ");
    }

    // ---------- Phiếu kết quả ở khu nội bộ (UC.9.7, UC.9.9) ----------

    @Test
    @DisplayName("chỉ lập phiếu cho người đang trong phòng khám")
    void chiLapPhieuChoNguoiTrongPhong() {
        var staff = clinicStaff();
        var user = client().registerUser();
        long appt = todayAppointment(user.selfPatientId());
        var res = staff.post("/api/staff/appointments/" + appt + "/record", body("diagnosis", "x")).expectStatus(409);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("NOT_IN_PROGRESS");
    }

    @Test
    @DisplayName("hồ sơ bị khoá thì không lập phiếu mới và không sửa phiếu cũ")
    void hoSoBiKhoa() {
        var staff = clinicStaff();
        var admin = client().staff("admin@chuctu.vn");
        var user = client().registerUser();
        String patientId = user.selfPatientId();
        Visit visit = completedVisit(staff, patientId);
        admin.post("/api/admin/patients/" + patientId + "/lock", body()).expectStatus(200);
        staff.put("/api/staff/records/" + visit.record().get("id").stringValue(), body("diagnosis", "Sửa")).expectStatus(403);

        admin.post("/api/admin/patients/" + patientId + "/unlock", body()).expectStatus(200);
        long appt = todayAppointment(patientId);
        staff.post("/api/staff/appointments/" + appt + "/checkin", body()).expectStatus(200);
        callIn(staff, appt);
        admin.post("/api/admin/patients/" + patientId + "/lock", body()).expectStatus(200);
        staff.post("/api/staff/appointments/" + appt + "/record", body("diagnosis", "x")).expectStatus(403);
        // Bị chặn thì phiếu vẫn đang khám, không có bệnh án
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM medical_records WHERE appointment_id = ?", Integer.class, appt)).isZero();
    }

    @Test
    @DisplayName("ghi lại nội dung đổi của ngày tái khám và ghi chú điều trị, sửa giống hệt thì không ghi")
    void ghiLaiNoiDungDoi() {
        var staff = clinicStaff();
        var user = client().registerUser();
        String followUp = futureDate(7);
        Visit visit = completedVisit(staff, user.selfPatientId(),
                body("diagnosis", "Đau gối", "treatmentNotes", body("acupuncture", "Huyệt A")));
        String id = visit.record().get("id").stringValue();

        JsonNode edited = staff.put("/api/staff/records/" + id, body("followUpDate", followUp,
                "treatmentNotes", body("acupuncture", "Huyệt B", "other", "Chườm ấm"))).expectStatus(200).json().get("record");
        assertThat(edited.get("followUpDate").stringValue()).isEqualTo(followUp);
        assertThat(edited.get("treatmentNotes").get("acupuncture").stringValue()).isEqualTo("Huyệt B");
        assertThat(edited.get("treatmentNotes").get("other").stringValue()).isEqualTo("Chườm ấm");
        assertThat(edited.get("editHistory")).hasSize(1);
        JsonNode changes = edited.get("editHistory").get(0).get("changes");
        assertThat(changes.get("followUpDate").get("from").isNull()).isTrue();
        assertThat(changes.get("followUpDate").get("to").stringValue()).isEqualTo(followUp);
        assertThat(changes.get("treatmentNotes.acupuncture").get("from").stringValue()).isEqualTo("Huyệt A");
        assertThat(changes.get("treatmentNotes.acupuncture").get("to").stringValue()).isEqualTo("Huyệt B");
        assertThat(changes.get("treatmentNotes.other").get("from").isNull()).isTrue();
        assertThat(changes.get("treatmentNotes.other").get("to").stringValue()).isEqualTo("Chườm ấm");

        JsonNode same = staff.put("/api/staff/records/" + id, body("followUpDate", followUp,
                "treatmentNotes", body("acupuncture", "Huyệt B"))).expectStatus(200).json().get("record");
        assertThat(same.get("editHistory")).hasSize(1);

        JsonNode cleared = staff.put("/api/staff/records/" + id, body("followUpDate", null)).expectStatus(200).json().get("record");
        assertThat(cleared.get("followUpDate").isNull()).isTrue();
        assertThat(cleared.get("editHistory")).hasSize(2);
    }

    @Test
    @DisplayName("nhân viên xem phiếu theo mã phiếu kết quả và theo phiếu khám")
    void nhanVienXemPhieu() {
        var staff = clinicStaff();
        var user = client().registerUser();
        Visit visit = completedVisit(staff, user.selfPatientId());
        String id = visit.record().get("id").stringValue();

        var detail = staff.get("/api/staff/appointments/" + visit.appointmentId()).expectStatus(200).json();
        assertThat(detail.get("record").get("id").stringValue()).isEqualTo(id);
        assertThat(detail.get("record").get("diagnosis").stringValue()).isEqualTo("Đau lưng");
        assertThat(detail.get("record").get("recordedByName").stringValue()).isNotEmpty();
    }

    @Test
    @DisplayName("người dùng thường không gọi được API phiếu kết quả nội bộ")
    void nguoiDungKhongGoiDuocApiNoiBo() {
        var staff = clinicStaff();
        var user = client().registerUser();
        Visit visit = completedVisit(staff, user.selfPatientId());
        String id = visit.record().get("id").stringValue();
        user.get("/api/staff/appointments/" + visit.appointmentId()).expectStatus(403);
        user.put("/api/staff/records/" + id, body("diagnosis", "Tự sửa")).expectStatus(403);
    }

    // ---------- Chỉ có ở bản Java ----------

    @Test
    @DisplayName("[java] test ngày: visitDate và followUpDate dạng YYYY-MM-DD, dob dạng YYYY-MM-DDT00:00:00.000Z")
    void javaTestNgay() {
        var staff = clinicStaff();
        var user = client().registerUser();
        String followUp = futureDate(10);
        Visit visit = completedVisit(staff, user.selfPatientId(),
                body("diagnosis", "Đau vai", "visitDate", "2026-03-05", "followUpDate", followUp));

        JsonNode record = visit.record();
        assertThat(record.get("visitDate").stringValue()).isEqualTo("2026-03-05");
        assertThat(record.get("followUpDate").stringValue()).isEqualTo(followUp);
        assertThat(record.get("patient").get("dob").stringValue()).isEqualTo("1960-01-01T00:00:00.000Z");
        assertThat(record.get("patient").get("birthYear").intValue()).isEqualTo(1960);
        assertThat(record.get("createdAt").stringValue()).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$");
        assertThat(record.get("appointment").get("id").stringValue()).isEqualTo(String.valueOf(visit.appointmentId()));
        assertThat(record.get("appointment").get("serviceLabel").stringValue()).isNotEmpty();
        // Người dùng thấy cùng dạng ngày
        JsonNode mine = user.get("/api/me/appointments/" + visit.appointmentId() + "/result").expectStatus(200).json().get("record");
        assertThat(mine.get("visitDate").stringValue()).isEqualTo("2026-03-05");
        assertThat(mine.get("patient").get("dob").stringValue()).isEqualTo("1960-01-01T00:00:00.000Z");
        // Không gửi visitDate thì lấy hôm nay (giờ Việt Nam)
        Visit today = completedVisit(staff, user.selfPatientId(), body("diagnosis", "Lần sau"));
        assertThat(today.record().get("visitDate").stringValue()).isEqualTo(VnTime.today(Instant.now()).toString());
    }

    @Test
    @DisplayName("[java] lưu phiếu: phiếu khám hoàn thành, check-in hoàn thành nhả chỗ, người kế tiếp vào chỗ đó")
    void javaLuuPhieuHoanThanhVaNhaGhe() {
        var staff = clinicStaff();
        var user = client().registerUser();
        Visit visit = completedVisit(staff, user.selfPatientId());
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, visit.appointmentId()))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM checkins WHERE appointment_id = ?", String.class, visit.appointmentId()))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT seat_no FROM checkins WHERE appointment_id = ?", Integer.class, visit.appointmentId())).isNull();
        // Lập lần hai cho cùng phiếu: không còn đang khám
        var again = staff.post("/api/staff/appointments/" + visit.appointmentId() + "/record", body("diagnosis", "x")).expectStatus(409);
        assertThat(again.json().get("error").get("code").stringValue()).isEqualTo("NOT_IN_PROGRESS");
    }

    @Test
    @DisplayName("[java] giờ ghi vào database là UTC đúng đồng hồ: updated_at của phiếu khám, completed_at, created_at của bệnh án")
    void javaGioGhiLaUtc() {
        var staff = clinicStaff();
        var user = client().registerUser();
        long appt = todayAppointment(user.selfPatientId());
        staff.post("/api/staff/appointments/" + appt + "/checkin", body()).expectStatus(200);
        callIn(staff, appt);
        Instant fixed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusMillis(123);
        clock.set(fixed);
        staff.post("/api/staff/appointments/" + appt + "/record", body("diagnosis", "Đau vai")).expectStatus(201);

        java.time.LocalDateTime expected = java.time.LocalDateTime.ofInstant(fixed, java.time.ZoneOffset.UTC);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM appointments WHERE id = ?", java.time.LocalDateTime.class, appt))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT completed_at FROM checkins WHERE appointment_id = ?", java.time.LocalDateTime.class, appt))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT created_at FROM medical_records WHERE appointment_id = ?", java.time.LocalDateTime.class, appt))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("[java] chủ tài khoản xem được kết quả khám của người thân, tài khoản khác thì 404")
    void javaXemKetQuaNguoiThan() {
        var staff = clinicStaff();
        var owner = client().registerUser("0917000031", "Chủ Tài Khoản");
        var other = client().registerUser("0917000032", "Người Lạ");
        String relative = owner.post("/api/me/patients",
                body("fullName", "Trần Thị Mẹ", "birthYear", 1940, "gender", "NU", "relationship", "KHAC")).expectStatus(201)
                .json().get("patient").get("id").stringValue();
        Visit visit = completedVisit(staff, relative, body("diagnosis", "Của mẹ"));

        JsonNode record = owner.get("/api/me/appointments/" + visit.appointmentId() + "/result").expectStatus(200).json().get("record");
        assertThat(record.get("diagnosis").stringValue()).isEqualTo("Của mẹ");
        assertThat(record.get("patient").get("id").stringValue()).isEqualTo(relative);
        other.get("/api/me/appointments/" + visit.appointmentId() + "/result").expectStatus(404);
    }

    @Test
    @DisplayName("[java] phiếu đã có bệnh án thì báo RECORD_EXISTS")
    void javaPhieuDaCoBenhAn() {
        var staff = clinicStaff();
        var user = client().registerUser();
        long appt = todayAppointment(user.selfPatientId());
        staff.post("/api/staff/appointments/" + appt + "/checkin", body()).expectStatus(200);
        callIn(staff, appt);
        Long patientId = jdbc.queryForObject("SELECT patient_id FROM appointments WHERE id = ?", Long.class, appt);
        Long staffId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'RECEPTIONIST'", Long.class);
        jdbc.update("INSERT INTO medical_records (patient_id, appointment_id, visit_date, recorded_by, created_at, updated_at) "
                + "VALUES (?, ?, CURDATE(), ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", patientId, appt, staffId);
        var res = staff.post("/api/staff/appointments/" + appt + "/record", body("diagnosis", "x")).expectStatus(409);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("RECORD_EXISTS");
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("[java] chỉ sửa được trong 24 giờ: đúng 24 giờ còn sửa được, quá 1 mili giây thì 403")
    void javaBienCuaSoSua() {
        var staff = clinicStaff();
        var user = client().registerUser();
        Visit visit = completedVisit(staff, user.selfPatientId());
        String id = visit.record().get("id").stringValue();
        Instant createdAt = jdbc.queryForObject("SELECT created_at FROM medical_records WHERE id = ?", java.time.LocalDateTime.class,
                Long.parseLong(id)).toInstant(java.time.ZoneOffset.UTC);
        assertThat(Instant.parse(visit.record().get("editableUntil").stringValue())).isEqualTo(createdAt.plusSeconds(24 * 3600));
        assertThat(visit.record().get("editableUntil").stringValue()).matches("^.*[.][0-9]{3}Z$");

        clock.set(createdAt.plusSeconds(24 * 3600));
        staff.put("/api/staff/records/" + id, body("diagnosis", "Đúng hạn")).expectStatus(200);
        clock.set(createdAt.plusSeconds(24 * 3600).plusMillis(1));
        staff.put("/api/staff/records/" + id, body("diagnosis", "Quá hạn")).expectStatus(403);
        assertThat(jdbc.queryForObject("SELECT diagnosis FROM medical_records WHERE id = ?", String.class, Long.parseLong(id)))
                .isEqualTo("Đúng hạn");
    }

    @Test
    @DisplayName("[java] lịch sử sửa giữ đúng thứ tự đã lưu, ghi người sửa")
    void javaLichSuSuaDungThuTu() {
        var staff = clinicStaff();
        var user = client().registerUser();
        Visit visit = completedVisit(staff, user.selfPatientId());
        String id = visit.record().get("id").stringValue();
        for (String d : new String[] {"Một", "Hai", "Ba", "Bốn"}) {
            staff.put("/api/staff/records/" + id, body("diagnosis", d)).expectStatus(200);
        }
        JsonNode history = staff.get("/api/staff/appointments/" + visit.appointmentId()).expectStatus(200).json().get("record").get("editHistory");
        assertThat(history).hasSize(4);
        String[] expectedFrom = {"Đau lưng", "Một", "Hai", "Ba"};
        String[] expectedTo = {"Một", "Hai", "Ba", "Bốn"};
        for (int i = 0; i < 4; i++) {
            assertThat(history.get(i).get("changes").get("diagnosis").get("from").stringValue()).isEqualTo(expectedFrom[i]);
            assertThat(history.get(i).get("changes").get("diagnosis").get("to").stringValue()).isEqualTo(expectedTo[i]);
            assertThat(history.get(i).get("editedByName").stringValue()).isNotEmpty();
            assertThat(history.get(i).get("editedAt").stringValue()).endsWith("Z");
        }
    }

    @Test
    @DisplayName("[java] hồ sơ bệnh nhân của tiếp tân liệt kê đúng bệnh án của hồ sơ đó")
    void javaHoSoBenhNhanCoBenhAn() {
        var staff = clinicStaff();
        var owner = client().registerUser("0917000021", "Người Có Bệnh Án");
        var other = client().registerUser("0917000022", "Người Khác Hẳn");
        String ownerPatient = owner.selfPatientId();
        Visit mine = completedVisit(staff, ownerPatient, body("diagnosis", "Của chủ", "visitDate", "2026-02-01"));
        Visit mine2 = completedVisit(staff, ownerPatient, body("diagnosis", "Của chủ lần hai", "visitDate", "2026-03-01"));
        completedVisit(staff, other.selfPatientId(), body("diagnosis", "Của người khác"));

        JsonNode records = staff.get("/api/staff/patients/" + ownerPatient).expectStatus(200).json().get("records");
        assertThat(records).hasSize(2);
        // Mới nhất trước
        assertThat(records.get(0).get("id").stringValue()).isEqualTo(mine2.record().get("id").stringValue());
        assertThat(records.get(1).get("id").stringValue()).isEqualTo(mine.record().get("id").stringValue());
        assertThat(records.get(0).get("diagnosis").stringValue()).isEqualTo("Của chủ lần hai");
        assertThat(records.get(0).get("recordedByName").stringValue()).isNotEmpty();
    }

    @Test
    @DisplayName("[java] dữ liệu gửi lên không hợp lệ thì báo VALIDATION theo từng ô")
    void javaKiemTraDuLieu() {
        var staff = clinicStaff();
        var user = client().registerUser();
        long appt = todayAppointment(user.selfPatientId());
        staff.post("/api/staff/appointments/" + appt + "/checkin", body()).expectStatus(200);
        callIn(staff, appt);
        var res = staff.post("/api/staff/appointments/" + appt + "/record", body("pulse", "x".repeat(51), "visitDate", "2026-02-30",
                "treatmentNotes", body("other", "y".repeat(2001)))).expectStatus(400);
        JsonNode fields = res.json().get("error").get("fields");
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("VALIDATION");
        assertThat(fields.get("pulse").stringValue()).isEqualTo("Tối đa 50 ký tự.");
        assertThat(fields.get("visitDate").stringValue()).isEqualTo("Ngày không hợp lệ.");
        assertThat(fields.get("treatmentNotes.other").stringValue()).isEqualTo("Tối đa 2000 ký tự.");
        // Không lập phiếu khi dữ liệu sai
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt)).isEqualTo("IN_PROGRESS");
    }
}
