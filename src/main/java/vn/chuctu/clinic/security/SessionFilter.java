package vn.chuctu.clinic.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;
import vn.chuctu.clinic.common.Errors;

/**
 * Đọc phiên đăng nhập nếu có, tương đương loadUser trong middleware/auth.js. Luôn tải lại tài khoản từ database
 * để tài khoản bị khoá hoặc đổi vai trò được áp dụng ngay ở yêu cầu kế tiếp (BR.32, BR.33).
 */
public class SessionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionFilter.class);

    private final SessionService sessions;
    private final UserRepository users;
    private final JsonErrorWriter errors;

    public SessionFilter(SessionService sessions, UserRepository users, JsonErrorWriter errors) {
        this.sessions = sessions;
        this.users = users;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        User user = readSession(request, response);
        // Mỗi tab gửi kèm tài khoản nó đang hiển thị. Cookie là chung cho mọi tab của trình duyệt, nên nếu tab khác
        // vừa đăng nhập tài khoản khác (hoặc đăng xuất) thì từ chối, tránh ghi thao tác nhầm tên người khác.
        // Các API /auth (xem phiên, đăng nhập, đăng xuất) vẫn cho qua để tab tự cập nhật lại.
        String expected = request.getHeader("x-session-user");
        if (expected != null && !expected.isEmpty() && !request.getRequestURI().startsWith("/api/auth/")
                && !expected.equals(user == null ? "" : String.valueOf(user.getId()))) {
            errors.write(response, Errors.conflict(
                    "Tài khoản trên trình duyệt này vừa thay đổi ở một thẻ khác. Trang sẽ tải lại theo tài khoản hiện tại.",
                    "SESSION_CHANGED"));
            return;
        }
        if (user != null) {
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private User readSession(HttpServletRequest request, HttpServletResponse response) {
        String token = cookie(request, SessionService.SESSION_COOKIE);
        if (token == null) return null;
        try {
            Long id = sessions.verify(token);
            Optional<User> user = id == null ? Optional.empty() : users.findById(id);
            if (user.isPresent() && user.get().isActive()) return user.get();
        } catch (RuntimeException e) {
            // Như Node: lỗi đọc database cũng coi là chưa đăng nhập và xoá cookie, không trả 500
            log.error("Không đọc được phiên đăng nhập", e);
        }
        sessions.clearSession(response);
        return null;
    }

    /** Giá trị cookie, null nếu không có hoặc rỗng (Node coi chuỗi rỗng là không có). */
    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (c.getName().equals(name) && !c.getValue().isEmpty()) return c.getValue();
        }
        return null;
    }
}
