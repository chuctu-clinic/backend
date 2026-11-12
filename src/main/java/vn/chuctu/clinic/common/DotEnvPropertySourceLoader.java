package vn.chuctu.clinic.common;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;

/**
 * Đọc .env theo UTF-8 (cú pháp KEY=VALUE như .properties). Bộ đọc .properties mặc định của Spring dùng ISO-8859-1 nên chữ
 * tiếng Việt trong .env (vd. tên trong MAIL_FROM) bị hỏng. Dùng qua gợi ý đuôi [.dotenv] trong spring.config.import.
 */
public class DotEnvPropertySourceLoader implements PropertySourceLoader {

    @Override
    public String[] getFileExtensions() {
        return new String[] {"dotenv"};
    }

    @Override
    public List<PropertySource<?>> load(String name, Resource resource) throws IOException {
        Properties props = new Properties();
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        if (props.isEmpty()) return List.of();
        Map<String, Object> map = new LinkedHashMap<>();
        props.stringPropertyNames().forEach(key -> map.put(key, props.getProperty(key)));
        return List.of(new MapPropertySource(name, map));
    }
}
