package vn.chuctu.clinic.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.chuctu.clinic.common.AppProperties;

/**
 * Cổng gửi mã 6 số tới số điện thoại (smsGateway.js).
 *
 * <ul>
 *   <li>console: server sinh mã và in ra log (phát triển, kiểm thử).
 *   <li>firebase: trình duyệt tự gửi và xác nhận mã qua Firebase Phone Auth; server không gửi gì, chỉ ghi nhận lần xin
 *       mã để giữ giới hạn mỗi giờ rồi kiểm ID token Firebase trả về.
 * </ul>
 */
@Component
public class SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(SmsGateway.class);

    private final AppProperties config;

    public SmsGateway(AppProperties config) {
        this.config = config;
    }

    /** Mã do trình duyệt gửi (Firebase) hay do server sinh và gửi. */
    public boolean usesFirebase() {
        return config.smsProvider.equals("firebase");
    }

    public void sendVerificationCode(String phone, String code, String purpose) {
        switch (config.smsProvider) {
            case "console" -> {
                if (!config.isTest) log.info("[OTP] {} {}: {}", purpose, phone, code);
            }
            case "firebase" -> {
                // Trình duyệt gửi tin qua Firebase
            }
            default -> throw new IllegalStateException("SMS_PROVIDER không hợp lệ: " + config.smsProvider);
        }
    }

    /** Khi phát triển, trả mã về cho giao diện để thử luồng mà không cần điện thoại thật. */
    public boolean exposeCodeForDev() {
        return !config.isProd && config.smsProvider.equals("console");
    }
}
