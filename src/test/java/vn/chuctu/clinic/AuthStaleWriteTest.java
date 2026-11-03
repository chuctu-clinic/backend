package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;
import vn.chuctu.clinic.auth.AuthService;
import vn.chuctu.clinic.auth.OtpService.Proof;
import vn.chuctu.clinic.common.ApiException;

/**
 * Admin sửa tài khoản đúng lúc một bước chậm (bcrypt, kiểm token) đang chạy giữa lúc đọc và lúc ghi của đăng nhập hay đổi
 * mật khẩu: thay đổi của admin không được bị ghi đè bằng bản đọc cũ. Node (Mongoose save) chỉ ghi các trường đã sửa.
 * Chỗ chen vào là một lớp bọc UserRepository: ngay sau khi tìm xong tài khoản, chạy việc "admin" rồi trả bản cũ.
 */
@Import(AuthStaleWriteTest.StaleReads.class)
class AuthStaleWriteTest extends IntegrationTest {

    /** Việc admin làm ngay sau lần tìm tài khoản đầu tiên của luồng đang chạy. */
    static final AtomicReference<Runnable> admin = new AtomicReference<>();

    /** Bọc UserRepository: sau mỗi findBy*, chạy việc của admin một lần rồi trả bản đã đọc (nay đã cũ). */
    @TestConfiguration
    static class StaleReads {
        @Bean
        static BeanPostProcessor staleUserReads() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof UserRepository)) return bean;
                    return Proxy.newProxyInstance(UserRepository.class.getClassLoader(), new Class<?>[] {UserRepository.class},
                            (proxy, method, args) -> {
                                Object found;
                                try {
                                    found = method.invoke(bean, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                                if (method.getName().startsWith("find")) {
                                    Runnable job = admin.getAndSet(null);
                                    if (job != null) job.run();
                                }
                                return found;
                            });
                }
            };
        }
    }

    @Autowired AuthService auth;

    private void interceptLoads() {
        admin.set(null);
    }

    private void adminLocksAndRenames(long id) {
        admin.set(() -> jdbc.update("UPDATE users SET is_active = 0, full_name = 'Admin đã đổi tên', "
                + "role = 'ADMIN', email = 'moi@chuctu.vn', password_hash = 'admin-temp-hash', must_change_password = 1 "
                + "WHERE id = ?", id));
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT is_active, full_name, role, email, password_hash, must_change_password, last_login_at "
                + "FROM users WHERE id = ?", id);
    }

    private void assertAdminChangesKept(long id) {
        var r = row(id);
        assertThat(r.get("is_active")).isIn(false, 0);
        assertThat(r.get("full_name")).isEqualTo("Admin đã đổi tên");
        assertThat(r.get("role")).isEqualTo("ADMIN");
        assertThat(r.get("email")).isEqualTo("moi@chuctu.vn");
    }

    @Test
    @DisplayName("[java] đăng nhập thiết bị đã xác nhận không ghi đè thay đổi đồng thời của admin")
    void startLoginKhongGhiDe() {
        User user = createUser("USER");
        auth.trustDevice(user.getId(), "dev-1");
        interceptLoads();
        adminLocksAndRenames(user.getId());
        // Bản đọc cũ vẫn đang hoạt động nên đăng nhập đi tiếp; điều cần giữ là các cột admin vừa đổi
        auth.startLogin(user.getPhone(), "dev-1");
        assertAdminChangesKept(user.getId());
        assertThat(row(user.getId()).get("last_login_at")).isNotNull();
    }

    @Test
    @DisplayName("[java] đăng nhập bằng mã xác nhận thiết bị mới không ghi đè thay đổi đồng thời của admin")
    void completeLoginKhongGhiDe() {
        User user = createUser("USER");
        String code = auth.resendCode(user.getPhone(), "NEW_DEVICE").get("devCode").toString();
        interceptLoads();
        adminLocksAndRenames(user.getId());
        auth.completeLogin(user.getPhone(), new Proof(code, null));
        assertAdminChangesKept(user.getId());
        assertThat(row(user.getId()).get("last_login_at")).isNotNull();
    }

    @Test
    @DisplayName("[java] đăng nhập tiếp tân bằng mật khẩu không ghi đè thay đổi đồng thời của admin")
    void staffLoginKhongGhiDe() {
        User staff = users.findByEmail("letan@chuctu.vn").orElseThrow();
        String hashBefore = staff.getPasswordHash();
        interceptLoads();
        adminLocksAndRenames(staff.getId());
        auth.staffLogin("letan@chuctu.vn", "ChucTu@2026");
        assertAdminChangesKept(staff.getId());
        assertThat(row(staff.getId()).get("password_hash")).isEqualTo("admin-temp-hash").isNotEqualTo(hashBefore);
        assertThat(row(staff.getId()).get("last_login_at")).isNotNull();
    }

    @Test
    @DisplayName("[java] đổi mật khẩu không ghi đè thay đổi khác của admin, chỉ đổi mật khẩu khi mật khẩu cũ còn nguyên")
    void changePasswordKhongGhiDe() {
        User staff = users.findByEmail("letan@chuctu.vn").orElseThrow();
        interceptLoads();
        // Admin đổi mọi thứ trừ mật khẩu
        admin.set(() -> jdbc.update("UPDATE users SET is_active = 0, full_name = 'Admin đã đổi tên', role = 'ADMIN', "
                + "email = 'moi@chuctu.vn' WHERE id = ?", staff.getId()));
        auth.changePassword(staff.getId(), "ChucTu@2026", "MatKhauMoi@2026");
        assertAdminChangesKept(staff.getId());
        assertThat(row(staff.getId()).get("password_hash")).isNotEqualTo(staff.getPasswordHash());
        assertThat(row(staff.getId()).get("must_change_password")).isIn(false, 0);
    }

    @Test
    @DisplayName("[java] đổi mật khẩu đúng lúc admin cấp mật khẩu tạm thì báo mật khẩu hiện tại chưa đúng, giữ mật khẩu tạm")
    void changePasswordGapMatKhauTam() {
        User staff = users.findByEmail("letan@chuctu.vn").orElseThrow();
        interceptLoads();
        adminLocksAndRenames(staff.getId());
        assertThatThrownBy(() -> auth.changePassword(staff.getId(), "ChucTu@2026", "MatKhauMoi@2026"))
                .isInstanceOf(ApiException.class).hasMessage("Mật khẩu hiện tại chưa đúng.");
        assertThat(row(staff.getId()).get("password_hash")).isEqualTo("admin-temp-hash");
        assertThat(row(staff.getId()).get("must_change_password")).isIn(true, 1);
    }
}
