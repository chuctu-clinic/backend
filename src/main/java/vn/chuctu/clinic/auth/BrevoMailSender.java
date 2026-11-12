package vn.chuctu.clinic.auth;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.common.AppProperties;

/**
 * Gửi thư qua API HTTPS của Brevo (cổng 443). Dùng khi máy chủ triển khai chặn SMTP ra ngoài (Railway gói Free/Hobby
 * chặn cổng 25/465/587). Địa chỉ trong MAIL_FROM phải là người gửi đã xác minh trong Brevo.
 */
public class BrevoMailSender implements MailSender {

    static final URI ENDPOINT = URI.create("https://api.brevo.com/v3/smtp/email");
    static final URI ACCOUNT = URI.create("https://api.brevo.com/v3/account");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final AppProperties config;
    private final JsonMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public BrevoMailSender(AppProperties config, JsonMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    /** Thân yêu cầu JSON; {@code from} có thể có dạng "Tên <địa chỉ>". */
    String requestBody(String to, String from, String subject, String text, String html) {
        InternetAddress sender;
        try {
            sender = new InternetAddress(from);
        } catch (AddressException e) {
            throw new IllegalStateException("MAIL_FROM không hợp lệ: " + e.getMessage(), e);
        }
        Map<String, String> senderJson = new LinkedHashMap<>();
        if (sender.getPersonal() != null) senderJson.put("name", sender.getPersonal());
        senderJson.put("email", sender.getAddress());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", senderJson);
        body.put("to", List.of(Map.of("email", to)));
        body.put("subject", subject);
        body.put("textContent", text);
        body.put("htmlContent", html);
        return mapper.writeValueAsString(body);
    }

    /**
     * Gọi nhẹ GET /v3/account để key được tính là đang dùng: Brevo tự huỷ API key bỏ không 90 ngày, mà phòng khám có thể
     * nhiều tháng không ai quên mật khẩu. Key cần quyền Account: Read. Ném lỗi nếu Brevo không nhận key.
     */
    public void keepAlive() {
        HttpRequest request = HttpRequest.newBuilder(ACCOUNT)
                .timeout(TIMEOUT)
                .header("api-key", config.brevoApiKey)
                .header("accept", "application/json")
                .GET()
                .build();
        check(call(request));
    }

    private HttpResponse<String> call(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Không kết nối được Brevo: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bị ngắt khi gọi Brevo", e);
        }
    }

    private static void check(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Brevo trả về " + response.statusCode() + ": " + response.body());
        }
    }

    @Override
    public void send(String to, String from, String subject, String text, String html) {
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(TIMEOUT)
                .header("api-key", config.brevoApiKey)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(to, from, subject, text, html)))
                .build();
        check(call(request));
    }
}
