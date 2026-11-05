package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.chuctu.clinic.patient.Patient;
import vn.chuctu.clinic.patient.PatientRepository;

/** [java] Tài khoản người dùng luôn có hồ sơ chính chủ: đổi vai trò sang Người dùng, tiếp tân tạo hồ sơ trùng số tài khoản. */
class AccountSelfProfileTest extends IntegrationTest {

    private static final String TEMP = "MatKhauTam1";

    @Autowired PatientRepository patients;

    private TestClient admin() {
        return client().staff("admin@chuctu.vn");
    }

    private long idOf(String phone) {
        return jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }

    private int selfProfiles(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM patients WHERE user_id = ? AND relationship = 'BAN_THAN'", Integer.class, userId);
    }

    private long newReceptionist(TestClient admin, String phone) {
        admin.post("/api/admin/accounts", body("fullName", "Lễ Tân Cũ", "phone", phone, "role", "RECEPTIONIST", "email",
                phone + "@chuctu.vn", "tempPassword", TEMP)).expectStatus(201);
        return idOf(phone);
    }

    @Test
    @DisplayName("[java] đổi tài khoản nội bộ sang Người dùng thì tạo hồ sơ chính chủ")
    void doiSangNguoiDungTaoHoSo() {
        var admin = admin();
        long id = newReceptionist(admin, "0920000101");
        assertThat(selfProfiles(id)).isZero();

        admin.put("/api/admin/accounts/" + id + "/role", body("role", "USER")).expectStatus(200);

        assertThat(selfProfiles(id)).isEqualTo(1);
        var self = patients.findFirstByUserIdAndRelationshipOrderByIdAsc(id, "BAN_THAN").orElseThrow();
        assertThat(self.getPhone()).isEqualTo("0920000101");
        assertThat(self.getFullName()).isEqualTo("Lễ Tân Cũ");

        // Đổi qua lại lần nữa không sinh thêm hồ sơ
        admin.put("/api/admin/accounts/" + id + "/role", body("role", "RECEPTIONIST")).expectStatus(200);
        admin.put("/api/admin/accounts/" + id + "/role", body("role", "USER")).expectStatus(200);
        assertThat(selfProfiles(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("[java] đổi sang Người dùng mà đã có hồ sơ tiếp tân tạo cùng số thì nối hồ sơ đó, giữ mã bệnh nhân")
    void doiSangNguoiDungNoiHoSoCu() {
        var admin = admin();
        var staff = client().staff();
        long id = newReceptionist(admin, "0920000102");
        var created = staff.post("/api/staff/patients", body("fullName", "Lễ Tân Cũ", "birthYear", "1980", "phone", "0920000102"))
                .expectStatus(201).json().get("patient");
        long orphanId = Long.parseLong(created.get("id").stringValue());
        assertThat(created.get("userId").isNull()).isTrue();

        admin.put("/api/admin/accounts/" + id + "/role", body("role", "USER")).expectStatus(200);

        Patient linked = patients.findById(orphanId).orElseThrow();
        assertThat(linked.getUserId()).isEqualTo(id);
        assertThat(linked.getRelationship()).isEqualTo("BAN_THAN");
        assertThat(linked.getPatientCode()).isEqualTo(created.get("patientCode").stringValue());
        assertThat(selfProfiles(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("[java] tiếp tân tạo hồ sơ trùng số của tài khoản chưa có hồ sơ: hồ sơ thành hồ sơ chính chủ, người dùng thấy trên website")
    void tiepTanTaoHoSoNoiTaiKhoan() {
        var user = client().registerUser();
        long userId = idOf("0911000001");
        // Dữ liệu cũ: tài khoản người dùng bị mất hồ sơ chính chủ
        jdbc.update("DELETE FROM patients WHERE user_id = ?", userId);

        var created = client().staff().post("/api/staff/patients", body("fullName", "Người Dùng Thử", "birthYear", "1960", "phone", "0911000001"))
                .expectStatus(201).json().get("patient");

        assertThat(created.get("userId").stringValue()).isEqualTo(String.valueOf(userId));
        assertThat(created.get("relationship").stringValue()).isEqualTo("BAN_THAN");
        var mine = user.get("/api/me/patients").expectStatus(200).json().get("items");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("id").stringValue()).isEqualTo(created.get("id").stringValue());
    }

    @Test
    @DisplayName("[java] số của tài khoản nội bộ thì hồ sơ tiếp tân tạo không gắn tài khoản")
    void soTaiKhoanNoiBoKhongNoi() {
        var admin = admin();
        newReceptionist(admin, "0920000103");
        var created = client().staff().post("/api/staff/patients", body("fullName", "Người Khác", "birthYear", "1970", "phone", "0920000103"))
                .expectStatus(201).json().get("patient");
        assertThat(created.get("userId").isNull()).isTrue();
    }
}
