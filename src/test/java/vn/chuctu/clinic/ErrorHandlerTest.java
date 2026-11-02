package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.common.GlobalExceptionHandler;

class ErrorHandlerTest extends IntegrationTest {

    @Autowired GlobalExceptionHandler handler;
    @Autowired JsonMapper mapper;

    /** Gọi trực tiếp bộ xử lý lỗi, trả về mã HTTP và nội dung phản hồi. */
    private ResponseEntity<?> handle(Exception e) {
        return handler.handle(e);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(ResponseEntity<?> response) {
        return (Map<String, Object>) ((Map<String, Object>) response.getBody()).get("error");
    }

    @Test
    @DisplayName("đường dẫn không tồn tại trả về 404 dạng JSON")
    void duongDanKhongTonTai() {
        var res = client().get("/api/khong-co").expectStatus(404);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("NOT_FOUND");
        assertThat(res.json().get("error").get("message").stringValue()).isEqualTo("Không tìm thấy đường dẫn.");
        assertThat(res.headers().firstValue("content-type").orElse("")).startsWith("application/json");
    }

    @Test
    @DisplayName("JSON sai cú pháp trả về 400")
    void jsonSaiCuPhap() {
        var res = client().post("/api/auth/_probe/login", "{sai").expectStatus(400);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("BAD_JSON");
        assertThat(res.json().get("error").get("message").stringValue()).isEqualTo("Dữ liệu gửi lên không hợp lệ.");
    }

    @Test
    @DisplayName("mã không đúng định dạng được coi là không tìm thấy")
    void maKhongDungDinhDang() {
        var browser = loginAs(createUser("USER"));
        var res = browser.get("/api/me/_probe/appointments/khong-phai-id").expectStatus(404);
        assertThat(res.json().get("error").get("code").stringValue()).isEqualTo("NOT_FOUND");
        assertThat(res.json().get("error").get("message").stringValue()).isEqualTo("Không tìm thấy dữ liệu.");
    }

    @Test
    @DisplayName("trùng khoá duy nhất trả về 409")
    void trungKhoa() {
        for (Exception e : new Exception[] {new DuplicateKeyException("E11000"), new DataIntegrityViolationException("E11000")}) {
            var response = handle(e);
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(error(response)).containsEntry("code", "DUPLICATE").containsEntry("message", "Dữ liệu đã tồn tại.");
        }
    }

    @Test
    @DisplayName("lỗi không lường trước trả về 500 và không lộ chi tiết bên trong")
    void loiKhongLuongTruoc() {
        var response = handle(new RuntimeException("jdbc:mysql://user:pass@host mất kết nối"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(error(response)).containsEntry("code", "SERVER_ERROR");
        assertThat(mapper.writeValueAsString(response.getBody())).doesNotContain("mysql", "pass");
    }

    @Test
    @DisplayName("[java] sai phương thức cũng trả 404 NOT_FOUND như Express")
    void saiPhuongThuc() {
        var res = client().post("/api/auth/_probe/instants", "{}").expectStatus(404);
        assertThat(res.json().get("error").get("message").stringValue()).isEqualTo("Không tìm thấy đường dẫn.");
    }
}
