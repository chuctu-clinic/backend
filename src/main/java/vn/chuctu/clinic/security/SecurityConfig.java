package vn.chuctu.clinic.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.header.Header;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.common.Errors;

/**
 * Chỉ dùng Spring Security cho phiên và phân quyền: không form login, không CSRF (cookie SameSite, API JSON), không
 * session của servlet. Quyền theo URL như requireUser, requireStaff, requireAdmin; các đường dẫn khác tự kiểm
 * trong controller bằng CurrentUser.require().
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final String[] STAFF = {"RECEPTIONIST", "DOCTOR", "TECHNICIAN", "ADMIN"};

    private static final String FORBIDDEN_MESSAGE_ATTRIBUTE = SecurityConfig.class.getName() + ".forbiddenMessage";

    /** Các header tương đương helmet({ contentSecurityPolicy: false, crossOriginResourcePolicy: cross-origin }). */
    private static final List<Header> HELMET_HEADERS = List.of(
            new Header("Cross-Origin-Opener-Policy", "same-origin"),
            new Header("Cross-Origin-Resource-Policy", "cross-origin"),
            new Header("Origin-Agent-Cluster", "?1"),
            new Header("Referrer-Policy", "no-referrer"),
            new Header("Strict-Transport-Security", "max-age=31536000; includeSubDomains"),
            new Header("X-Content-Type-Options", "nosniff"),
            new Header("X-DNS-Prefetch-Control", "off"),
            new Header("X-Download-Options", "noopen"),
            new Header("X-Frame-Options", "SAMEORIGIN"),
            new Header("X-Permitted-Cross-Domain-Policies", "none"),
            new Header("X-XSS-Protection", "0"));

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AppProperties config, JsonMapper mapper, JsonErrorWriter errors,
            SessionService sessions, UserRepository users) throws Exception {
        var cors = new CorsHeadersFilter(config.clientOrigin);
        var originCheck = new OriginCheckFilter(config.clientOrigin, errors);
        var bodyLimit = new BodyLimitFilter(mapper, errors);
        var session = new SessionFilter(sessions, users, errors);
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.defaultsDisabled().addHeaderWriter(new StaticHeadersWriter(HELMET_HEADERS)))
                .addFilterAfter(cors, HeaderWriterFilter.class)
                .addFilterAfter(originCheck, CorsHeadersFilter.class)
                .addFilterAfter(bodyLimit, OriginCheckFilter.class)
                .addFilterAfter(session, BodyLimitFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/me/**").access(roles("USER"))
                        // Bác sĩ: khám sơ bộ, chỉ định, lập và sửa phiếu kết quả
                        .requestMatchers("/api/staff/exam/**").access(roles("DOCTOR", "ADMIN"))
                        .requestMatchers(HttpMethod.POST, "/api/staff/appointments/*/record").access(roles("DOCTOR", "ADMIN"))
                        .requestMatchers(HttpMethod.PUT, "/api/staff/records/*").access(roles("DOCTOR", "ADMIN"))
                        // Kỹ thuật viên: chỗ điều trị và báo cáo điều trị
                        .requestMatchers("/api/staff/treatment/**", "/api/staff/queue/auto-invite").access(roles("TECHNICIAN", "ADMIN"))
                        // Mọi nhân viên: xem hàng đợi, phiếu khám, hồ sơ và phiếu kết quả; sửa thông tin tài khoản của mình
                        .requestMatchers(HttpMethod.GET, "/api/staff/queue", "/api/staff/appointments/*", "/api/staff/records/*",
                                "/api/staff/patients/*").access(roles(STAFF))
                        .requestMatchers("/api/staff/account/**").access(roles(STAFF))
                        // Còn lại của khu nội bộ (lịch trong ngày, check-in, đặt hộ, hồ sơ bệnh nhân): tiếp tân
                        .requestMatchers("/api/staff/**").access(roles("RECEPTIONIST", "ADMIN"))
                        .requestMatchers("/api/admin/**").access(roles("ADMIN"))
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> errors.write(response, Errors.unauthorized()))
                        .accessDeniedHandler((request, response, ex) -> {
                            Object message = request.getAttribute(FORBIDDEN_MESSAGE_ATTRIBUTE);
                            errors.write(response, message == null ? Errors.forbidden() : Errors.forbidden(message.toString()));
                        }));
        return http.build();
    }

    /** Như requireRole: sai vai trò thì 403; tài khoản nội bộ còn dùng mật khẩu tạm phải đổi mật khẩu trước. */
    private static AuthorizationManager<RequestAuthorizationContext> roles(String... allowed) {
        Set<String> roles = Set.of(allowed);
        return (authentication, context) -> {
            var auth = authentication.get();
            // Chưa đăng nhập: bộ lọc bỏ qua token ẩn danh và trả 401 qua authenticationEntryPoint
            if (!(auth.getPrincipal() instanceof User user)) return new AuthorizationDecision(false);
            if (!roles.contains(user.getRole())) return new AuthorizationDecision(false);
            if (user.isMustChangePassword() && user.isStaff()) {
                HttpServletRequest request = context.getRequest();
                request.setAttribute(FORBIDDEN_MESSAGE_ATTRIBUTE, "Cô chú cần đổi mật khẩu tạm trước khi tiếp tục.");
                return new AuthorizationDecision(false);
            }
            return new AuthorizationDecision(true);
        };
    }

    /** Không có tài khoản trong bộ nhớ: chặn Spring tự tạo mật khẩu ngẫu nhiên cho user mặc định. */
    @Bean
    AuthenticationManager authenticationManager() {
        return authentication -> {
            throw new ProviderNotFoundException("Đăng nhập do AuthService xử lý, không qua AuthenticationManager");
        };
    }
}
