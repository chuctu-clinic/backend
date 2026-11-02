package vn.chuctu.clinic.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Giống cors({ origin: CLIENT_ORIGIN, credentials: true }) của Node: origin không có trong danh sách thì chỉ không
 * thêm header CORS (trình duyệt tự chặn), không trả 403 như CorsFilter của Spring. Yêu cầu preflight trả 204.
 */
public class CorsHeadersFilter extends OncePerRequestFilter {

    private final List<String> allowedOrigins;

    public CorsHeadersFilter(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        boolean preflight = "OPTIONS".equals(request.getMethod());
        response.addHeader("Vary", "Origin");
        if (origin != null && allowedOrigins.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
        }
        response.setHeader("Access-Control-Allow-Credentials", "true");
        if (preflight) {
            response.setHeader("Access-Control-Allow-Methods", "GET,HEAD,PUT,PATCH,POST,DELETE");
            String requested = request.getHeader("Access-Control-Request-Headers");
            if (requested != null) {
                response.setHeader("Access-Control-Allow-Headers", requested);
                response.addHeader("Vary", "Access-Control-Request-Headers");
            }
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            response.setContentLength(0);
            return;
        }
        chain.doFilter(request, response);
    }
}
