package vn.chuctu.clinic.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.common.AppProperties;

/** Chọn bộ gửi thư theo EMAIL_PROVIDER: brevo gửi qua API HTTPS, còn lại dùng SMTP (chỉ kết nối ở lần gửi đầu). */
@Configuration(proxyBeanMethods = false)
public class MailConfig {

    @Bean
    MailSender mailSender(AppProperties config, JsonMapper mapper) {
        return config.emailProvider.equals("brevo") ? new BrevoMailSender(config, mapper) : new SmtpMailSender(config);
    }
}
