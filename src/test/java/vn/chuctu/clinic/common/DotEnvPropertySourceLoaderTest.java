package vn.chuctu.clinic.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.mock.env.MockEnvironment;

class DotEnvPropertySourceLoaderTest {

    @Test
    @DisplayName("[java] .env đọc theo UTF-8 nên tên tiếng Việt trong MAIL_FROM không bị hỏng")
    void docUtf8(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "# ghi chú\nEMAIL_PROVIDER=brevo\nMAIL_FROM=Phòng khám Chúc Từ <a@gmail.com>\nSMTP_PASS=\n",
                StandardCharsets.UTF_8);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.config.import", "optional:file:" + env.toUri().getPath() + "[.dotenv]");
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        assertThat(environment.getProperty("MAIL_FROM")).isEqualTo("Phòng khám Chúc Từ <a@gmail.com>");
        assertThat(environment.getProperty("EMAIL_PROVIDER")).isEqualTo("brevo");
        assertThat(environment.getProperty("SMTP_PASS")).isEmpty();
    }
}
