package vn.chuctu.clinic.seed;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/**
 * Với profile demo: trỏ datasource sang chuctu_demo và tạo lại database đó. Chạy khi chuẩn bị context, trước mọi bean
 * (datasource, Flyway), đăng ký ở META-INF/spring.factories. Không có profile demo thì không làm gì.
 */
public final class DemoDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment env = context.getEnvironment();
        if (!env.acceptsProfiles(Profiles.of("demo"))) return;
        boolean production = "production".equals(env.getProperty("NODE_ENV"));
        String url = DemoDatabase.demoUrl(env.getProperty("spring.datasource.url"));
        DemoDatabase.prepare(url, env.getProperty("spring.datasource.username"), env.getProperty("spring.datasource.password"),
                production);
        env.getPropertySources().addFirst(new MapPropertySource("demoDatabase", Map.of("spring.datasource.url", url)));
    }
}
