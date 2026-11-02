package vn.chuctu.clinic.seed;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Nạp dữ liệu mỗi lần khởi động (Dockerfile của Node chạy {@code node src/seed.js && node src/index.js}), chỉ tạo cái còn
 * thiếu. Profile demo (thay cho MongoDB trong bộ nhớ của Node) nạp thêm dữ liệu mẫu.
 * <p>Seed chạy ở {@link #afterSingletonsInstantiated()}: sau Flyway và JPA, nhưng trước khi Tomcat mở cổng, nên seed thất bại
 * (ví dụ production thiếu SEED_ADMIN_PASSWORD) thì ứng dụng không bao giờ nhận yêu cầu nào.
 * Tham số {@code --seed} chỉ nạp rồi thoát (npm run seed); việc thoát làm ở runner, sau khi context đã khởi động xong.
 */
@Component
public class StartupSeed implements SmartInitializingSingleton, ApplicationRunner {

    private final Seeder seeder;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private final boolean enabled;

    public StartupSeed(Seeder seeder, Environment environment, ConfigurableApplicationContext context,
            @Value("${clinic.seed-on-startup:true}") boolean enabled) {
        this.seeder = seeder;
        this.environment = environment;
        this.context = context;
        this.enabled = enabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (enabled) seeder.run(environment.acceptsProfiles(Profiles.of("demo")));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (enabled && args.containsOption("seed")) System.exit(SpringApplication.exit(context, () -> 0));
    }
}
