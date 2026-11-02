package vn.chuctu.clinic.queue;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Cài đặt vận hành (getSetting, setSetting của Node), lưu ở bảng settings dưới dạng JSON. */
@Component
public class Settings {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public Settings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Giá trị đã lưu, hoặc {@code fallback} nếu khoá chưa có. */
    public <T> T get(String key, T fallback, Class<T> type) {
        List<String> rows = jdbc.query("SELECT `value` FROM settings WHERE name = ?", (rs, i) -> rs.getString(1), key);
        if (rows.isEmpty()) return fallback;
        String json = rows.get(0);
        return json == null ? null : MAPPER.readValue(json, type);
    }

    public void set(String key, Object value) {
        String json = MAPPER.writeValueAsString(value);
        jdbc.update("INSERT INTO settings (name, `value`) VALUES (?, CAST(? AS JSON)) ON DUPLICATE KEY UPDATE `value` = CAST(? AS JSON)",
                key, json, json);
    }
}
