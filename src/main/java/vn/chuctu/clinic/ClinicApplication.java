package vn.chuctu.clinic;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ClinicApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ClinicApplication.class);
        // --seed chỉ nạp dữ liệu rồi thoát (npm run seed). Tham số dòng lệnh thắng application.yml, nên cổng ngẫu nhiên
        // chắc chắn có hiệu lực và không đụng server đang chạy ở cổng 4000.
        if (Arrays.asList(args).contains("--seed")) {
            String[] withPort = Arrays.copyOf(args, args.length + 1);
            withPort[args.length] = "--server.port=0";
            args = withPort;
        }
        app.run(args);
    }
}
