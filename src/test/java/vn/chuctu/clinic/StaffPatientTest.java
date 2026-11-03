package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.patient.Patient;
import vn.chuctu.clinic.patient.PatientRepository;

/** Cổng từ test/staff-patient.test.js, thêm các test [java] cho những route hồ sơ bệnh nhân của tiếp tân và Admin. */
class StaffPatientTest extends IntegrationTest {

    @Autowired PatientRepository patients;

    private Patient newPatient() {
        return newPatient("CT-BN-TEST-1", "Nguyễn Văn Thử", "0916000001");
    }

    private Patient newPatient(String code, String fullName, String phone) {
        Patient p = new Patient();
        p.setPatientCode(code);
        p.setFullName(fullName);
        p.setPhone(phone);
        p.setBirthYear(1950);
        p.setGender("NAM");
        p.setAddress("Phường 25, TP. Hồ Chí Minh");
        return patients.save(p);
    }

    private static JsonNode fields(TestClient.Result res) {
        return res.json().get("error").get("fields");
    }

    private static String errorCode(TestClient.Result res) {
        return res.json().get("error").get("code").stringValue();
    }

    // ---------- Tiếp tân sửa thông tin hồ sơ bệnh nhân ----------

    @Test
    @DisplayName("sửa đủ các trường như lúc người dùng tạo hồ sơ: ngày sinh, giới tính, địa chỉ, CCCD, email")
    void suaDuCacTruong() {
        var staff = client().staff();
        Patient p = newPatient();
        var res = staff.patch("/api/staff/patients/" + p.getId(), body(
                "fullName", "Nguyễn Văn Sửa",
                "phone", "0916000002",
                "dob", "1952-03-12",
                "gender", "NU",
                "address", "57/8 Điện Biên Phủ, Phường Thạnh Mỹ Tây, TP. Hồ Chí Minh",
                "nationalId", "079052000123",
                "email", "Sua@Email.com")).expectStatus(200);
        var after = res.json().get("patient");
        assertThat(after.get("fullName").stringValue()).isEqualTo("Nguyễn Văn Sửa");
        assertThat(after.get("phone").stringValue()).isEqualTo("0916000002");
        assertThat(after.get("dob").stringValue()).startsWith("1952-03-12");
        assertThat(after.get("birthYear").intValue()).isEqualTo(1952);
        assertThat(after.get("gender").stringValue()).isEqualTo("NU");
        assertThat(after.get("address").stringValue()).isEqualTo("57/8 Điện Biên Phủ, Phường Thạnh Mỹ Tây, TP. Hồ Chí Minh");
        assertThat(after.get("nationalId").stringValue()).isEqualTo("079052000123");
        assertThat(after.get("email").stringValue()).isEqualTo("sua@email.com");
    }

    @Test
    @DisplayName("chỉ gửi vài trường thì các trường còn lại giữ nguyên (không bị xoá)")
    void chiGuiVaiTruong() {
        var staff = client().staff();
        Patient p = newPatient();
        jdbc.update("UPDATE patients SET national_id = '079052000123', email = 'giu@email.com' WHERE id = ?", p.getId());
        var res = staff.patch("/api/staff/patients/" + p.getId(), body("fullName", "Tên Mới")).expectStatus(200).json();
        assertThat(res.get("patient").get("fullName").stringValue()).isEqualTo("Tên Mới");
        assertThat(res.get("patient").get("phone").stringValue()).isEqualTo("0916000001");
        assertThat(res.get("patient").get("nationalId").stringValue()).isEqualTo("079052000123");
        assertThat(res.get("patient").get("email").stringValue()).isEqualTo("giu@email.com");
    }

    @Test
    @DisplayName("CCCD và email sai định dạng thì báo lỗi theo trường")
    void saiDinhDangBaoLoiTheoTruong() {
        var staff = client().staff();
        Patient p = newPatient();
        var res = staff.patch("/api/staff/patients/" + p.getId(), body("nationalId", "123", "email", "khong-dung")).expectStatus(400);
        assertThat(fields(res).has("nationalId")).isTrue();
        assertThat(fields(res).has("email")).isTrue();
    }

    // ---------- Các route còn lại của hồ sơ bệnh nhân ----------

    @Test
    @DisplayName("[java] tạo hồ sơ tại quầy: bắt buộc họ tên, năm sinh, số điện thoại (BR.25), mã bệnh nhân tăng dần")
    void javaTaoHoSoTaiQuay() {
        var staff = client().staff();
        var res = staff.post("/api/staff/patients", body()).expectStatus(400);
        assertThat(fields(res).get("fullName").stringValue()).isEqualTo("Cô chú vui lòng nhập họ và tên.");
        assertThat(fields(res).get("phone").stringValue()).isEqualTo("Cô chú vui lòng nhập số điện thoại.");
        assertThat(fields(res).get("birthYear").stringValue()).isEqualTo("Vui lòng chọn năm sinh.");

        var created = staff.post("/api/staff/patients", body("fullName", "Trần Văn Quầy", "phone", "0916000010", "birthYear", 1948))
                .expectStatus(201).json().get("patient");
        assertThat(created.get("patientCode").stringValue()).matches("^CT-BN-\\d{2}-0001$");
        assertThat(created.get("birthYear").intValue()).isEqualTo(1948);
        assertThat(created.get("userId").isNull()).isTrue();
        assertThat(created.get("relationship").isNull()).isTrue();
        assertThat(created.get("gender").isNull()).isTrue();
        long creator = jdbc.queryForObject("SELECT created_by FROM patients WHERE id = ?", Long.class, Long.parseLong(created.get("id").stringValue()));
        assertThat(creator).isEqualTo(jdbc.queryForObject("SELECT id FROM users WHERE email = 'letan@chuctu.vn'", Long.class));
        var second = staff.post("/api/staff/patients", body("fullName", "Trần Văn Hai", "phone", "0916000011", "birthYear", 1949))
                .expectStatus(201).json().get("patient");
        assertThat(second.get("patientCode").stringValue()).matches("^CT-BN-\\d{2}-0002$");
    }

    @Test
    @DisplayName("[java] trùng số điện thoại thì báo DUPLICATE_PHONE, xác nhận rồi mới tạo (BR.35)")
    void javaTrungSoDienThoai() {
        var staff = client().staff();
        newPatient();
        var form = body("fullName", "Người Trùng Số", "phone", "0916000001", "birthYear", 1950);
        var res = staff.post("/api/staff/patients", form).expectStatus(409);
        assertThat(errorCode(res)).isEqualTo("DUPLICATE_PHONE");
        assertThat(res.json().get("error").get("message").stringValue())
                .isEqualTo("Đã có hồ sơ dùng số điện thoại này. Kiểm tra lại trước khi tạo hồ sơ mới.");
        var dups = res.json().get("error").get("duplicates");
        assertThat(dups.size()).isEqualTo(1);
        assertThat(dups.get(0).get("fullName").stringValue()).isEqualTo("Nguyễn Văn Thử");

        var confirmed = new java.util.LinkedHashMap<>(form);
        confirmed.put("confirmDuplicate", true);
        staff.post("/api/staff/patients", confirmed).expectStatus(201);
        assertThat(patients.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("[java] tra số điện thoại trùng, bỏ qua hồ sơ đang xem")
    void javaTraSoTrung() {
        var staff = client().staff();
        Patient a = newPatient();
        Patient b = newPatient("CT-BN-TEST-2", "Người Thứ Hai", "0916000001");
        newPatient("CT-BN-TEST-3", "Số Khác", "0916000003");

        assertThat(staff.get("/api/staff/patients/duplicates?phone=0916000001").expectStatus(200).json().get("items").size()).isEqualTo(2);
        var excluded = staff.get("/api/staff/patients/duplicates?phone=0916000001&excludeId=" + a.getId()).expectStatus(200).json().get("items");
        assertThat(excluded.size()).isEqualTo(1);
        assertThat(excluded.get(0).get("id").stringValue()).isEqualTo(String.valueOf(b.getId()));
        assertThat(staff.get("/api/staff/patients/duplicates").expectStatus(200).json().get("items").size()).isZero();
        staff.get("/api/staff/patients/duplicates?phone=0916000001&excludeId=khong-phai-id").expectStatus(404);
    }

    @Test
    @DisplayName("[java] danh sách phân trang, lọc theo khoá, kèm ngày khám gần nhất")
    void javaDanhSach() {
        var staff = client().staff();
        Patient first = newPatient("CT-BN-TEST-1", "Bệnh Nhân Một", "0916000001");
        Patient second = newPatient("CT-BN-TEST-2", "Bệnh Nhân Hai", "0916000002");
        Patient third = newPatient("CT-BN-TEST-3", "Bệnh Nhân Ba", "0916000003");
        jdbc.update("UPDATE patients SET created_at = '2026-01-01 00:00:00.000' WHERE id = ?", first.getId());
        jdbc.update("UPDATE patients SET created_at = '2026-01-02 00:00:00.000' WHERE id = ?", second.getId());
        jdbc.update("UPDATE patients SET created_at = '2026-01-03 00:00:00.000', is_locked = 1, locked_at = UTC_TIMESTAMP(3) WHERE id = ?",
                third.getId());

        var page = staff.get("/api/staff/patients?limit=2").expectStatus(200).json();
        assertThat(page.get("total").intValue()).isEqualTo(3);
        assertThat(page.get("page").intValue()).isEqualTo(1);
        assertThat(page.get("limit").intValue()).isEqualTo(2);
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("patientCode").stringValue()).isEqualTo("CT-BN-TEST-3");
        assertThat(page.get("items").get(1).get("patientCode").stringValue()).isEqualTo("CT-BN-TEST-2");
        var next = staff.get("/api/staff/patients?limit=2&page=2").expectStatus(200).json();
        assertThat(next.get("items").size()).isEqualTo(1);
        assertThat(next.get("items").get(0).get("patientCode").stringValue()).isEqualTo("CT-BN-TEST-1");

        assertThat(staff.get("/api/staff/patients?locked=true").expectStatus(200).json().get("total").intValue()).isEqualTo(1);
        assertThat(staff.get("/api/staff/patients?locked=false").expectStatus(200).json().get("total").intValue()).isEqualTo(2);
        assertThat(staff.get("/api/staff/patients?q=0916000002").expectStatus(200).json().get("items").get(0).get("fullName").stringValue())
                .isEqualTo("Bệnh Nhân Hai");
        assertThat(staff.get("/api/staff/patients?q=ct-bn-test-1").expectStatus(200).json().get("total").intValue()).isEqualTo(1);

        // ngày khám gần nhất lấy từ bệnh án, dạng YYYY-MM-DD
        long staffId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'letan@chuctu.vn'", Long.class);
        for (String date : new String[] {"2026-02-01", "2026-04-15"}) {
            jdbc.update("INSERT INTO appointments (ticket_code, patient_id, service_type, slot_date, slot_start, expected_time, status, "
                    + "source, created_by, created_at, updated_at) VALUES (?, ?, 'CHAM_CUU', ?, '09:00', UTC_TIMESTAMP(3), 'COMPLETED', "
                    + "'COUNTER', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", "CT-PK-26-" + date, first.getId(), date, staffId);
            long apptId = jdbc.queryForObject("SELECT id FROM appointments WHERE ticket_code = ?", Long.class, "CT-PK-26-" + date);
            jdbc.update("INSERT INTO medical_records (patient_id, appointment_id, visit_date, recorded_by, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", first.getId(), apptId, date, staffId);
        }
        for (JsonNode item : staff.get("/api/staff/patients").expectStatus(200).json().get("items")) {
            if (item.get("patientCode").stringValue().equals("CT-BN-TEST-1")) {
                assertThat(item.get("lastVisitDate").stringValue()).isEqualTo("2026-04-15");
            } else {
                assertThat(item.get("lastVisitDate").isNull()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("[java] xem hồ sơ bệnh nhân, không thấy thì 404")
    void javaXemHoSo() {
        var staff = client().staff();
        Patient p = newPatient();
        var detail = staff.get("/api/staff/patients/" + p.getId()).expectStatus(200).json();
        assertThat(detail.get("patient").get("id").stringValue()).isEqualTo(String.valueOf(p.getId()));
        assertThat(detail.get("appointments").isArray()).isTrue();
        assertThat(detail.get("records").isArray()).isTrue();
        var missing = staff.get("/api/staff/patients/999999").expectStatus(404);
        assertThat(missing.json().get("error").get("message").stringValue()).isEqualTo("Không tìm thấy hồ sơ bệnh nhân.");
        staff.get("/api/staff/patients/khong-phai-id").expectStatus(404);
    }

    @Test
    @DisplayName("[java] hồ sơ đang khoá thì tiếp tân không sửa được")
    void javaHoSoKhoaKhongSua() {
        var staff = client().staff();
        var admin = client().staff("admin@chuctu.vn");
        Patient p = newPatient();
        admin.post("/api/admin/patients/" + p.getId() + "/lock", Map.of()).expectStatus(200);
        var res = staff.patch("/api/staff/patients/" + p.getId(), body("fullName", "Tên Khác")).expectStatus(403);
        assertThat(res.json().get("error").get("message").stringValue()).isEqualTo("Hồ sơ đang bị khoá nên không sửa được thông tin.");
    }

    @Test
    @DisplayName("[java] Admin khoá và mở khoá hồ sơ, tiếp tân không có quyền")
    void javaKhoaMoKhoa() {
        var admin = client().staff("admin@chuctu.vn");
        Patient p = newPatient();
        var locked = admin.post("/api/admin/patients/" + p.getId() + "/lock", Map.of()).expectStatus(200).json().get("patient");
        assertThat(locked.get("isLocked").booleanValue()).isTrue();
        assertThat(locked.get("lockedAt").stringValue()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(patients.findById(p.getId()).orElseThrow().isLocked()).isTrue();

        client().staff().post("/api/admin/patients/" + p.getId() + "/unlock", Map.of()).expectStatus(403);

        var unlocked = admin.post("/api/admin/patients/" + p.getId() + "/unlock", Map.of()).expectStatus(200).json().get("patient");
        assertThat(unlocked.get("isLocked").booleanValue()).isFalse();
        assertThat(unlocked.get("lockedAt").isNull()).isTrue();
        admin.post("/api/admin/patients/999999/lock", Map.of()).expectStatus(404);
    }

    @Test
    @DisplayName("[java] khoá hồ sơ chỉ đổi cột khoá, không ghi đè thay đổi khác")
    void javaKhoaChiDoiCotKhoa() {
        var admin = client().staff("admin@chuctu.vn");
        Patient p = newPatient();
        jdbc.update("UPDATE patients SET full_name = 'Tên Đổi Cùng Lúc' WHERE id = ?", p.getId());
        admin.post("/api/admin/patients/" + p.getId() + "/lock", Map.of()).expectStatus(200);
        assertThat(patients.findById(p.getId()).orElseThrow().getFullName()).isEqualTo("Tên Đổi Cùng Lúc");
    }

    @Test
    @DisplayName("[java] người dùng thường không vào được các route hồ sơ của tiếp tân")
    void javaNguoiDungKhongVaoDuoc() {
        var user = client().registerUser();
        user.get("/api/staff/patients").expectStatus(403);
        user.post("/api/admin/patients/1/lock", Map.of()).expectStatus(403);
        client().get("/api/staff/patients").expectStatus(401);
    }

    @Test
    @DisplayName("[java] trang quá xa trả danh sách rỗng và vẫn đủ tổng số như Node, không lỗi 500")
    void trangQuaXa() {
        newPatient();
        var res = client().staff().get("/api/staff/patients?page=30000000&limit=100").expectStatus(200).json();
        assertThat(res.get("items").size()).isEqualTo(0);
        assertThat(res.get("total").intValue()).isEqualTo(1);
        assertThat(res.get("page").intValue()).isEqualTo(30000000);
    }
}
