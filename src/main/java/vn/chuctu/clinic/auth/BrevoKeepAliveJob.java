package vn.chuctu.clinic.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brevo tự huỷ API key không được dùng trong 90 ngày. Khi gửi thư qua Brevo, cứ 3 ngày gọi nhẹ Brevo một lần (lần đầu
 * 5 phút sau khi khởi động) để key không bị huỷ, và báo lỗi sớm trong log nếu key đã hỏng. Với console/smtp không làm gì.
 * Test đặt clinic.cleanup-job.enabled=false nên không chạy theo lịch; test gọi thẳng {@link #run()}.
 */
@Component
@EnableScheduling
public class BrevoKeepAliveJob {

    private static final Logger log = LoggerFactory.getLogger(BrevoKeepAliveJob.class);

    private final MailSender mailSender;
    private final boolean enabled;

    public BrevoKeepAliveJob(MailSender mailSender, @Value("${clinic.cleanup-job.enabled:true}") boolean enabled) {
        this.mailSender = mailSender;
        this.enabled = enabled;
    }

    @Scheduled(initialDelay = 5 * 60 * 1000L, fixedDelay = 3 * 24 * 60 * 60 * 1000L)
    void scheduled() {
        if (enabled) run();
    }

    /** Trả về true nếu đã gọi Brevo thành công; false nếu không dùng Brevo hoặc gọi lỗi (lỗi được ghi log). */
    public boolean run() {
        if (!(mailSender instanceof BrevoMailSender brevo)) return false;
        try {
            brevo.keepAlive();
            log.info("[Brevo] API key vẫn dùng được.");
            return true;
        } catch (RuntimeException e) {
            log.error("[Brevo] API key không dùng được, email OTP sẽ không gửi được. Kiểm tra BREVO_API_KEY "
                    + "(có thể đã hết hạn hoặc thiếu quyền Account: Read): {}", e.getMessage());
            return false;
        }
    }
}
