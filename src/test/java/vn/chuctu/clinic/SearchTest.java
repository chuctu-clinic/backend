package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.chuctu.clinic.patient.Patient;
import vn.chuctu.clinic.patient.PatientRepository;

/** Tìm hồ sơ bệnh nhân: không phân biệt hoa thường, phân biệt dấu, ký tự đặc biệt của LIKE là chữ thường. */
class SearchTest extends IntegrationTest {

    @Autowired PatientRepository patients;

    private TestClient staff;

    @BeforeEach
    void seedPatients() {
        add("CT-BN-TEST-1", "Nguyễn Văn A", "0916000001");
        add("CT-BN-TEST-2", "Tran_B", "0916000002");
        add("CT-BN-TEST-3", "100% Khoe", "0916000003");
        staff = client().staff();
    }

    private void add(String code, String fullName, String phone) {
        Patient p = new Patient();
        p.setPatientCode(code);
        p.setFullName(fullName);
        p.setPhone(phone);
        patients.save(p);
    }

    private List<String> search(String q) {
        var res = staff.get("/api/staff/patients?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)).expectStatus(200).json();
        List<String> names = new ArrayList<>();
        res.get("items").forEach(item -> names.add(item.get("fullName").stringValue()));
        return names;
    }

    @Test
    @DisplayName("[java] tìm không phân biệt hoa thường")
    void tim_khong_phan_biet_hoa_thuong() {
        assertThat(search("NGUYỄN")).containsExactly("Nguyễn Văn A");
        assertThat(search("khoe")).containsExactly("100% Khoe");
    }

    @Test
    @DisplayName("[java] tìm phân biệt dấu")
    void tim_phan_biet_dau() {
        assertThat(search("Nguyen")).isEmpty();
        assertThat(search("Nguyễn")).containsExactly("Nguyễn Văn A");
    }

    @Test
    @DisplayName("[java] ký tự đặc biệt là chữ thường")
    void ky_tu_dac_biet_la_chu_thuong() {
        assertThat(search("%")).containsExactly("100% Khoe");
        assertThat(search("_")).containsExactly("Tran_B");
        assertThat(search("\\")).isEmpty();
        assertThat(search("[a-z]")).isEmpty();
        assertThat(search(".*")).isEmpty();
    }

    @Test
    @DisplayName("[java] tìm theo mã bệnh nhân và số điện thoại, bỏ khoảng trắng hai đầu")
    void tim_theo_ma_va_so_dien_thoai() {
        assertThat(search("ct-bn-test-2")).containsExactly("Tran_B");
        assertThat(search("0916000003")).containsExactly("100% Khoe");
        assertThat(search("  0916000001  ")).containsExactly("Nguyễn Văn A");
    }
}
