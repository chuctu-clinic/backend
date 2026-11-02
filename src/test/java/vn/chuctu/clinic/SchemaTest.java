package vn.chuctu.clinic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** Kiểm các ràng buộc ở tầng bảng bằng SQL thật. Entity của từng bảng chưa có nên dùng JdbcTemplate. */
class SchemaTest extends IntegrationTest {

    private long staffId;
    private long patientId;
    private int ticketSeq;

    @BeforeEach
    void fixtures() {
        staffId = insert("INSERT INTO users (phone, full_name, role, created_at, updated_at) "
                + "VALUES ('0900000501', 'Tiếp tân', 'RECEPTIONIST', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        patientId = insert("INSERT INTO patients (patient_code, full_name, created_at, updated_at) "
                + "VALUES ('BN0001', 'Nguyễn Văn A', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        ticketSeq = 0;
    }

    private long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long appointment() {
        return insert("INSERT INTO appointments (ticket_code, patient_id, service_type, slot_date, slot_start, "
                + "expected_time, source, created_by, created_at, updated_at) "
                + "VALUES (?, ?, 'CHAM_CUU', '2026-11-02', '08:00', '2026-11-02 01:00:00.000', 'COUNTER', ?, "
                + "UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", "PK" + (++ticketSeq), patientId, staffId);
    }

    private long checkin(long appointmentId, String visitDate, String status, Integer seatNo) {
        return insert("INSERT INTO checkins (appointment_id, patient_id, visit_date, check_in_time, priority_group, "
                + "status, seat_no, created_at) VALUES (?, ?, ?, UTC_TIMESTAMP(3), 'NORMAL', ?, ?, UTC_TIMESTAMP(3))",
                appointmentId, patientId, visitDate, status, seatNo);
    }

    private long post(String title, Integer pinned) {
        return insert("INSERT INTO posts (title, category, author_id, author_name, status, pinned, created_at, updated_at) "
                + "VALUES (?, 'TIN_TUC', ?, 'Admin', 'PUBLISHED', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                title, staffId, pinned);
    }

    private long user(String phone, String email) {
        return insert("INSERT INTO users (phone, email, full_name, role, created_at, updated_at) "
                + "VALUES (?, ?, 'Người dùng', 'USER', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", phone, email);
    }

    @Test
    @DisplayName("[java] có đủ 13 bảng của thiết kế")
    void co_du_13_bang() {
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history' "
                + "ORDER BY table_name", String.class);
        assertEquals(List.of("appointments", "checkins", "counters", "medical_record_edits", "medical_records", "patients",
                "posts", "settings", "slot_counters", "trusted_devices", "uploads", "users", "verification_codes"), tables);
    }

    @Test
    @DisplayName("[java] chặn hai người cùng một ghế")
    void chan_hai_nguoi_cung_mot_ghe() {
        checkin(appointment(), "2026-11-02", "IN_ROOM", 1);
        assertThrows(DuplicateKeyException.class, () -> checkin(appointment(), "2026-11-02", "IN_ROOM", 1));
        // Ghế khác cùng ngày, hoặc cùng ghế ngày khác, đều được
        assertDoesNotThrow(() -> checkin(appointment(), "2026-11-02", "IN_ROOM", 2));
        assertDoesNotThrow(() -> checkin(appointment(), "2026-11-03", "IN_ROOM", 1));
    }

    @Test
    @DisplayName("[java] cho phép ghế trùng khi không trong phòng")
    void cho_phep_ghe_trung_khi_khong_trong_phong() {
        assertDoesNotThrow(() -> {
            checkin(appointment(), "2026-11-02", "WAITING", null);
            checkin(appointment(), "2026-11-02", "WAITING", null);
            checkin(appointment(), "2026-11-02", "WAITING", null);
            // Ghế 1 đang có người trong phòng; ghế ghi trên dòng đã xong hay đã hoàn tác không tính là chiếm ghế
            checkin(appointment(), "2026-11-02", "IN_ROOM", 1);
            checkin(appointment(), "2026-11-02", "COMPLETED", 1);
            checkin(appointment(), "2026-11-02", "REVERTED", 1);
        });
    }

    @Test
    @DisplayName("[java] chặn hai check-in hiệu lực cùng phiếu")
    void chan_hai_checkin_hieu_luc_cung_phieu() {
        long first = appointment();
        assertDoesNotThrow(() -> {
            checkin(first, "2026-11-02", "REVERTED", null);
            checkin(first, "2026-11-02", "REVERTED", null);
            checkin(first, "2026-11-02", "WAITING", null);
        });
        // WAITING đang hiệu lực nên COMPLETED hay IN_ROOM thêm vào cùng phiếu đều bị chặn
        assertThrows(DuplicateKeyException.class, () -> checkin(first, "2026-11-02", "COMPLETED", null));
        assertThrows(DuplicateKeyException.class, () -> checkin(first, "2026-11-02", "IN_ROOM", 3));
    }

    @Test
    @DisplayName("[java] chỉ một bài được ghim")
    void chi_mot_bai_duoc_ghim() {
        post("Bài ghim", 1);
        assertThrows(DuplicateKeyException.class, () -> post("Bài ghim thứ hai", 1));
        // Bài không ghim có thể nhiều
        assertDoesNotThrow(() -> {
            post("Thường 1", null);
            post("Thường 2", null);
        });
        // Cột chỉ nhận 1 hoặc NULL
        var e = assertThrows(DataAccessException.class, () -> post("Ghim 0", 0));
        assertTrue(e.getMessage().contains("ck_posts_pinned"), e.getMessage());
    }

    @Test
    @DisplayName("[java] nhiều tài khoản không có email")
    void nhieu_tai_khoan_khong_co_email() {
        assertDoesNotThrow(() -> {
            user("0900000011", null);
            user("0900000012", null);
            user("0900000013", null);
        });
    }

    @Test
    @DisplayName("[java] email không phân biệt hoa thường")
    void email_khong_phan_biet_hoa_thuong() {
        user("0900000021", "A@x.vn");
        assertThrows(DuplicateKeyException.class, () -> user("0900000022", "a@x.vn"));
    }
}
