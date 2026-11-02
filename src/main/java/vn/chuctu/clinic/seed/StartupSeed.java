package vn.chuctu.clinic.seed;

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
 * thiếu. Profile demo (thay cho MongoDB trong bộ nhớ của Node) nạp thêm dữ liệu mẫu. Tham số {@code --seed} chỉ nạp rồi
 * thoát (npm run seed). Thất bại (ví dụ production thiếu SEED_ADMIN_PASSWORD) thì ứng dụng không khởi động.
 */
@Component
public class StartupSeed implements ApplicationRunner {

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
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        seeder.run(environment.acceptsProfiles(Profiles.of("demo")));
        if (args.containsOption("seed")) System.exit(SpringApplication.exit(context, () -> 0));
    }
}
