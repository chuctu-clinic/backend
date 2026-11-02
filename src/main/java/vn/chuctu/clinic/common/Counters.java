package vn.chuctu.clinic.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Bộ đếm tăng nguyên tử để sinh mã bệnh nhân và mã phiếu khám không trùng ({@code nextSeq} của Node). */
@Component
public class Counters {

    private static final String BUMP = "INSERT INTO counters (name, seq) VALUES (?, LAST_INSERT_ID(1)) "
            + "ON DUPLICATE KEY UPDATE seq = LAST_INSERT_ID(seq + 1)";

    private final JdbcTemplate jdbc;

    public Counters(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Số kế tiếp của khoá (bắt đầu từ 1). LAST_INSERT_ID là biến của từng kết nối nên hai câu lệnh phải cùng một
     * kết nối; trong giao dịch đang mở thì dùng chính kết nối của giao dịch đó.
     */
    public long next(String key) {
        return jdbc.execute((Connection con) -> {
            try (PreparedStatement bump = con.prepareStatement(BUMP)) {
                bump.setString(1, key);
                bump.executeUpdate();
            }
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT LAST_INSERT_ID()")) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }
}
