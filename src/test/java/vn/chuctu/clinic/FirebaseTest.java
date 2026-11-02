package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vn.chuctu.clinic.auth.SmsGateway;
import vn.chuctu.clinic.security.SessionService;

/** Chế độ Firebase (firebase.test.js): trình duyệt gửi và xác nhận mã, server kiểm ID token. Phần kiểm token do FakeFirebase thay. */
class FirebaseTest extends IntegrationTest {

    @MockitoSpyBean SmsGateway sms;

    private static final Map<String, Object> FORM =
            Map.of("fullName", "Lê Thị Firebase", "phone", "0911000077", "dob", "1960-02-03", "gender", "NU");

    @BeforeEach
    void firebaseMode() {
        doReturn(true).when(sms).usesFirebase();
        doReturn(false).when(sms).exposeCodeForDev();
    }

    private static String code(TestClient.Result res) {
        return res.json().get("error").get("code").stringValue();
    }

    private TestClient.Result verify(TestClient agent, String idToken) {
        return agent.post("/api/auth/register/verify", Map.of("phone", "0911000077", "idToken", idToken));
    }

    @Test
    @Disabled("Task 10")
    @DisplayName("giao diện biết phải gửi mã qua Firebase")
    void giaoDienBietGuiMaQuaFirebase() {}

    @Test
    @DisplayName("đăng ký: server không sinh mã, nhận ID token đúng số thì tạo tài khoản; không dùng lại được")
    void dangKyBangIdToken() {
        var agent = client();
        var start = agent.post("/api/auth/register", FORM).expectStatus(200).json();
        assertThat(start.get("provider").stringValue()).isEqualTo("firebase");
        assertThat(start.has("devCode")).isFalse();

        firebase.phone = "+84911000077";
        var res = verify(agent, FakeFirebase.TOKEN).expectStatus(201);
        assertThat(res.json().get("user").get("fullName").stringValue()).isEqualTo("Lê Thị Firebase");
        // GET /api/me thuộc Task 6: ở đây chỉ kiểm có cookie phiên
        assertThat(res.headers().allValues("set-cookie")).anyMatch(c -> c.startsWith(SessionService.SESSION_COOKIE + "="));
    }

    @Test
    @DisplayName("token của số khác, token sai, token cũ đều bị từ chối")
    void tokenSaiBiTuChoi() {
        client().post("/api/auth/register", FORM).expectStatus(200);
        firebase.phone = "+84911999999";
        assertThat(code(verify(client(), FakeFirebase.TOKEN).expectStatus(400))).isEqualTo("PHONE_MISMATCH");

        assertThat(code(verify(client(), "token-gia-mao-khong-hop-le").expectStatus(400))).isEqualTo("TOKEN_INVALID");

        firebase.phone = "+84911000077";
        firebase.authAgeMillis = 20 * 60_000L;
        assertThat(code(verify(client(), FakeFirebase.TOKEN).expectStatus(400))).isEqualTo("CODE_EXPIRED");
    }

    @Test
    @DisplayName("mỗi lần xin mã chỉ đổi được một phiên đăng nhập")
    void moiLanXinMaChiDoiDuocMotPhien() {
        client().post("/api/auth/register", FORM).expectStatus(200);
        firebase.phone = "+84911000077";
        verify(client(), FakeFirebase.TOKEN).expectStatus(201);

        // Máy mới: xin mã một lần, xác nhận được một lần
        var other = client();
        var start = other.post("/api/auth/login", Map.of("phone", "0911000077")).expectStatus(200).json();
        assertThat(start.get("needCode").asBoolean()).isTrue();
        assertThat(start.get("provider").stringValue()).isEqualTo("firebase");
        other.post("/api/auth/login/verify", Map.of("phone", "0911000077", "idToken", FakeFirebase.TOKEN)).expectStatus(200);
        var again = client().post("/api/auth/login/verify", Map.of("phone", "0911000077", "idToken", FakeFirebase.TOKEN)).expectStatus(400);
        assertThat(code(again)).isEqualTo("CODE_EXPIRED");
    }

    @Test
    @DisplayName("[java] không có idToken thì CODE_REQUIRED, token không có số điện thoại thì PHONE_MISMATCH")
    void thieuTokenVaThieuSo() {
        client().post("/api/auth/register", FORM).expectStatus(200);
        var res = client().post("/api/auth/register/verify", Map.of("phone", "0911000077")).expectStatus(400);
        assertThat(code(res)).isEqualTo("CODE_REQUIRED");

        firebase.phone = null;
        assertThat(code(verify(client(), FakeFirebase.TOKEN).expectStatus(400))).isEqualTo("PHONE_MISMATCH");
    }
}
