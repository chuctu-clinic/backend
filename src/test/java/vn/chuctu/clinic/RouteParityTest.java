package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Danh sách đường dẫn của bản Node, viết tay từ backend/src/routes/*.routes.js (tiền tố do backend/src/app.js gắn), cộng
 * /api/health. Không sinh từ các mapping của Java: thiếu một đường dẫn hay thừa một đường dẫn đều làm test hỏng.
 */
class RouteParityTest extends IntegrationTest {

    /** Phương thức và đường dẫn; tham số đường dẫn viết {} cho khỏi phụ thuộc tên biến. */
    private static final List<String> NODE_ROUTES = List.of(
            // app.js
            "GET /api/health",
            // public.routes.js (gắn ở /api)
            "GET /api/meta",
            "GET /api/uploads/{}",
            "GET /api/posts",
            "GET /api/posts/{}",
            // auth.routes.js (/api/auth)
            "GET /api/auth/me",
            "POST /api/auth/register",
            "POST /api/auth/register/verify",
            "POST /api/auth/login",
            "POST /api/auth/login/verify",
            "POST /api/auth/otp/resend",
            "POST /api/auth/staff/login",
            "POST /api/auth/staff/forgot-password",
            "POST /api/auth/staff/forgot-password/verify",
            "POST /api/auth/staff/reset-password",
            "POST /api/auth/change-password",
            "POST /api/auth/logout",
            // me.routes.js (/api/me)
            "GET /api/me",
            "PATCH /api/me",
            "GET /api/me/patients",
            "POST /api/me/patients",
            "GET /api/me/patients/{}",
            "PATCH /api/me/patients/{}",
            "DELETE /api/me/patients/{}",
            "GET /api/me/availability",
            "POST /api/me/appointments",
            "GET /api/me/appointments",
            "GET /api/me/appointments/{}",
            "POST /api/me/appointments/{}/cancel",
            "GET /api/me/appointments/{}/result",
            "GET /api/me/history",
            // staff.routes.js (/api/staff)
            "PATCH /api/staff/account",
            "POST /api/staff/account/email",
            "POST /api/staff/account/email/verify",
            "GET /api/staff/appointments",
            "GET /api/staff/appointments/dates",
            "GET /api/staff/appointments/lookup",
            "GET /api/staff/appointments/{}",
            "GET /api/staff/availability",
            "POST /api/staff/appointments",
            "POST /api/staff/appointments/{}/reschedule",
            "POST /api/staff/appointments/{}/cancel",
            "POST /api/staff/appointments/{}/checkin",
            "POST /api/staff/appointments/{}/undo-checkin",
            "POST /api/staff/appointments/{}/enter",
            "POST /api/staff/appointments/{}/undo-enter",
            "GET /api/staff/queue",
            "POST /api/staff/queue/invite",
            "POST /api/staff/queue/{}/undo-enter",
            "PUT /api/staff/queue/auto-invite",
            "GET /api/staff/patients",
            "GET /api/staff/patients/duplicates",
            "POST /api/staff/patients",
            "GET /api/staff/patients/{}",
            "PATCH /api/staff/patients/{}",
            "POST /api/staff/appointments/{}/record",
            "GET /api/staff/records/{}",
            "PUT /api/staff/records/{}",
            // admin.routes.js (/api/admin)
            "GET /api/admin/dashboard",
            "GET /api/admin/accounts",
            "POST /api/admin/accounts",
            "GET /api/admin/accounts/{}",
            "PATCH /api/admin/accounts/{}",
            "POST /api/admin/accounts/{}/lock",
            "POST /api/admin/accounts/{}/unlock",
            "PUT /api/admin/accounts/{}/role",
            "POST /api/admin/accounts/{}/reset-password",
            "POST /api/admin/patients/{}/lock",
            "POST /api/admin/patients/{}/unlock",
            "POST /api/admin/uploads",
            "GET /api/admin/posts",
            "POST /api/admin/posts",
            "GET /api/admin/posts/{}",
            "PUT /api/admin/posts/{}",
            "POST /api/admin/posts/{}/pin",
            "POST /api/admin/posts/{}/unpin",
            "DELETE /api/admin/posts/{}");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    /** Mọi cặp phương thức + đường dẫn mà các controller của ứng dụng (vn.chuctu.clinic) đăng ký. */
    private Set<String> javaRoutes() {
        Set<String> out = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            // Chỉ tính controller của ứng dụng: bỏ /error của Spring Boot và mọi controller chỉ có trong src/test
            String type = handler.getBeanType().getName();
            if (!type.startsWith("vn.chuctu.clinic.") || type.startsWith("vn.chuctu.clinic.Test")) return;
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            assertThat(methods).as("mapping %s phải nêu phương thức", info).isNotEmpty();
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                for (RequestMethod method : methods) out.add(method + " " + pattern.replaceAll("\\{[^}/]*}", "{}"));
            }
        });
        return out;
    }

    @Test
    @DisplayName("[java] đủ 77 endpoint: đọc RequestMappingHandlerMapping, so với danh sách ghi cứng từ routes/*.routes.js")
    void du77Endpoint() {
        assertThat(NODE_ROUTES).hasSize(77).doesNotHaveDuplicates();
        Set<String> expected = new TreeSet<>(NODE_ROUTES);
        Set<String> actual = javaRoutes();

        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        assertThat(missing).as("Node có nhưng Java thiếu").isEmpty();
        assertThat(extra).as("Java có nhưng Node không có").isEmpty();
        assertThat(actual).hasSize(77);
    }

    @Test
    @DisplayName("[java] /api/health trả {\"ok\":true} không cần đăng nhập")
    void health() {
        assertThat(client().get("/api/health").expectStatus(200).text()).isEqualTo("{\"ok\":true}");
    }
}
