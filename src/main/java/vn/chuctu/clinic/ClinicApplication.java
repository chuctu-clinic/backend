package vn.chuctu.clinic;

import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ClinicApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ClinicApplication.class);
        // --seed chỉ nạp dữ liệu rồi thoát (npm run seed): dùng cổng ngẫu nhiên để không đụng server đang chạy ở cổng 4000
        if (Arrays.asList(args).contains("--seed")) app.setDefaultProperties(Map.of("server.port", "0"));
        app.run(args);
    }
}
