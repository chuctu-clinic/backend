package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;

class SessionFailureTest extends IntegrationTest {

    @MockitoSpyBean UserRepository repository;

    @Test
    @DisplayName("[java] lỗi đọc database khi tải phiên: xoá cookie và coi như chưa đăng nhập")
    void loiDatabaseKhiTaiPhien() {
        User user = createUser("USER");
        var browser = loginAs(user);
        doThrow(new QueryTimeoutException("mất kết nối")).when(repository).findById(anyLong());

        var anonymous = browser.get("/api/auth/me").expectStatus(200);
        assertThat(anonymous.json().get("user").isNull()).isTrue();
        assertThat(anonymous.headers().allValues("set-cookie"))
                .anySatisfy(c -> assertThat(c).startsWith("ct_session=;").contains("Max-Age=0"));
        loginAs(user).get("/api/me/_probe").expectStatus(401);
    }
}
