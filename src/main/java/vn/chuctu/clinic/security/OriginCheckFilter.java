package vn.chuctu.clinic.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.chuctu.clinic.common.Errors;

/**
 * Chống CSRF. Cookie phiên khi production là SameSite=None, nên một trang lạ có thể gửi form POST kèm cookie mà
 * không qua preflight CORS. Trình duyệt luôn gửi Origin với POST, PUT, PATCH, DELETE: Origin phải nằm trong
 * CLIENT_ORIGIN hoặc trùng địa chỉ của chính server (frontend cùng địa chỉ qua nginx hay proxy của Vite). Yêu cầu
 * không có Origin (curl, ứng dụng, kiểm thử) đi qua như trước.
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final List<String> allowedOrigins;
    private final JsonErrorWriter errors;

    public OriginCheckFilter(List<String> allowedOrigins, JsonErrorWriter errors) {
        this.allowedOrigins = allowedOrigins;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && UNSAFE_METHODS.contains(request.getMethod()) && !allowed(origin, request)) {
            errors.write(response, Errors.forbidden("Yêu cầu không đến từ trang của phòng khám."));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(String origin, HttpServletRequest request) {
        return allowedOrigins.contains(origin) || sameHost(origin, request.getServerName());
    }

    /**
     * Frontend cùng địa chỉ (nginx, proxy của Vite): chỉ so tên miền. Proxy có thể báo http khi trình duyệt dùng https
     * (TLS kết thúc ở lớp trước) và X-Forwarded-Host không kèm cổng, nên không so scheme hay cổng. Trang của kẻ tấn
     * công không thể mang tên miền của phòng khám.
     */
    private static boolean sameHost(String origin, String serverName) {
        try {
            String host = java.net.URI.create(origin).getHost();
            return host != null && host.equalsIgnoreCase(serverName);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
