package vn.chuctu.clinic.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.common.AppProperties;

/** Chỉ dựng thân yêu cầu, không bao giờ gọi Brevo. */
class BrevoMailSenderTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final BrevoMailSender sender = new BrevoMailSender(new AppProperties(new MockEnvironment()
            .withProperty("NODE_ENV", "test").withProperty("EMAIL_PROVIDER", "brevo")
            .withProperty("BREVO_API_KEY", "k").withProperty("MAIL_FROM", "x@gmail.com")), mapper);

    @Test
    @DisplayName("[java] thân yêu cầu Brevo tách tên hiển thị khỏi địa chỉ gửi và giữ tiếng Việt")
    void thanYeuCau() {
        JsonNode body = mapper.readTree(sender.requestBody("letan@chuctu.vn", "Phòng khám Chúc Từ <phongkham@example.com>",
                "Mã đặt lại mật khẩu", "Mã: 123456", "<p>123456</p>"));
        assertThat(body.get("sender").get("name").asString()).isEqualTo("Phòng khám Chúc Từ");
        assertThat(body.get("sender").get("email").asString()).isEqualTo("phongkham@example.com");
        assertThat(body.get("to").get(0).get("email").asString()).isEqualTo("letan@chuctu.vn");
        assertThat(body.get("subject").asString()).isEqualTo("Mã đặt lại mật khẩu");
        assertThat(body.get("textContent").asString()).isEqualTo("Mã: 123456");
        assertThat(body.get("htmlContent").asString()).isEqualTo("<p>123456</p>");
    }

    @Test
    @DisplayName("[java] MAIL_FROM chỉ có địa chỉ thì không gửi tên")
    void chiCoDiaChi() {
        JsonNode body = mapper.readTree(sender.requestBody("a@b.vn", "x@gmail.com", "s", "t", "h"));
        assertThat(body.get("sender").has("name")).isFalse();
        assertThat(body.get("sender").get("email").asString()).isEqualTo("x@gmail.com");
    }
}
