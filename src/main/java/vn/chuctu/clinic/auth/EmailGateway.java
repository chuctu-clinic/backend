package vn.chuctu.clinic.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.chuctu.clinic.common.ApiException;
import vn.chuctu.clinic.common.AppProperties;
import vn.chuctu.clinic.common.Rules;

/**
 * Cổng gửi mã 6 số tới email (emailGateway.js): quên mật khẩu, đổi email của tài khoản nội bộ.
 *
 * <ul>
 *   <li>console: in mã ra log (phát triển, kiểm thử). Khi production không gửi được gì nên báo lỗi rõ ràng.
 *   <li>smtp: gửi qua máy chủ SMTP (Gmail, Google Workspace, Brevo...), cấu hình SMTP_* và MAIL_FROM.
 * </ul>
 */
@Component
public class EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(EmailGateway.class);

    private record Content(String subject, String intro) {}

    private static final Content RESET_PASSWORD = new Content("Phòng khám Chúc Từ - Mã đặt lại mật khẩu",
            "Bạn vừa yêu cầu đặt lại mật khẩu tài khoản nội bộ của Phòng khám Chúc Từ.");
    private static final Content CHANGE_EMAIL = new Content("Phòng khám Chúc Từ - Mã xác minh email",
            "Bạn vừa đổi email của tài khoản nội bộ Phòng khám Chúc Từ sang địa chỉ này.");
    private static final String OUTRO = "Nếu không phải bạn yêu cầu, hãy bỏ qua email này. "
            + "Không đưa mã cho bất kỳ ai, kể cả nhân viên phòng khám.";

    private final AppProperties config;
    private final MailSender mailSender;

    public EmailGateway(AppProperties config, MailSender mailSender) {
        this.config = config;
        this.mailSender = mailSender;
    }

    private static Content content(String purpose) {
        return switch (purpose) {
            case "RESET_PASSWORD" -> RESET_PASSWORD;
            case "CHANGE_EMAIL" -> CHANGE_EMAIL;
            default -> null;
        };
    }

    public void sendCode(String email, String code, String purpose) {
        switch (config.emailProvider) {
            case "console" -> {
                if (config.isProd) {
                    throw new ApiException(503, "EMAIL_UNAVAILABLE", "Phòng khám chưa bật gửi email. Cô chú vui lòng liên hệ "
                            + "quản trị viên để được cấp mật khẩu tạm.", null);
                }
                if (!config.isTest) {
                    Content c = content(purpose);
                    log.info("[EMAIL] {} → {}: {}", c == null ? purpose : c.subject(), email, code);
                }
            }
            case "smtp" -> {
                Content c = content(purpose);
                String subject = c == null ? "Mã xác nhận — Phòng khám Chúc Từ" : c.subject();
                String intro = c == null ? "" : c.intro();
                String text = intro + "\n\nMã xác nhận: " + code + "\nMã dùng được trong " + Rules.OTP_TTL_MINUTES + " phút.\n\n"
                        + OUTRO + "\n\nPhòng khám Chúc Từ";
                try {
                    mailSender.send(email, config.mailFrom, subject, text);
                } catch (RuntimeException e) {
                    log.error("[EMAIL] Gửi qua SMTP không được: {}", e.getMessage());
                    throw new ApiException(502, "EMAIL_FAILED",
                            "Chưa gửi được email. Cô chú thử lại sau ít phút hoặc liên hệ quản trị viên.", null);
                }
            }
            default -> throw new IllegalStateException("EMAIL_PROVIDER không hợp lệ: " + config.emailProvider);
        }
    }

    /** Mã mẫu cố định (mặc định 123456) để thử luồng khi phát triển; kiểm thử và production luôn dùng mã ngẫu nhiên. */
    public String devFixedCode() {
        return config.env.equals("development") && config.emailProvider.equals("console") ? config.emailDevCode : null;
    }

    /** Khi phát triển, trả mã về cho giao diện để thử luồng mà không cần hộp thư thật. */
    public boolean exposeCodeForDev() {
        return !config.isProd && config.emailProvider.equals("console");
    }
}
