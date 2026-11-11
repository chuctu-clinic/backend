package vn.chuctu.clinic.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.chuctu.clinic.post.PostRepository;
import vn.chuctu.clinic.post.PostService;
import vn.chuctu.clinic.queue.QueueService;

/** Tổng quan trong ngày cho Admin (UC.2.3), dashboard.service.js. */
@Service
public class DashboardService {

    private final JdbcTemplate jdbc;
    private final PostRepository posts;
    private final PostService postService;
    private final QueueService queue;

    public DashboardService(JdbcTemplate jdbc, PostRepository posts, PostService postService, QueueService queue) {
        this.jdbc = jdbc;
        this.posts = posts;
        this.postService = postService;
        this.queue = queue;
    }

    /** Đếm theo một cột: khoá là giá trị cột (chỉ những giá trị có dòng), giá trị là số dòng. */
    private Map<String, Long> countBy(String sql, Object... args) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        }, args);
        return out;
    }

    public Map<String, Object> overview(LocalDate date, Instant now) {
        Map<String, Long> byStatus = countBy("SELECT status, COUNT(*) FROM appointments WHERE slot_date = ? GROUP BY status ORDER BY status", date);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long patients = jdbc.queryForObject("SELECT COUNT(*) FROM patients", Long.class);
        Map<String, Long> postCounts = countBy("SELECT status, COUNT(*) FROM posts GROUP BY status");
        Map<String, Long> accounts = countBy("SELECT role, COUNT(*) FROM users GROUP BY role ORDER BY role");
        Map<String, Object> state = queue.queueState(date, now);

        Map<String, Object> appointments = new LinkedHashMap<>();
        appointments.put("total", total);
        appointments.put("active", total - byStatus.getOrDefault("CANCELLED", 0L));
        appointments.put("byStatus", byStatus);

        Map<String, Object> postSummary = new LinkedHashMap<>();
        postSummary.put("published", postCounts.getOrDefault("PUBLISHED", 0L));
        postSummary.put("draft", postCounts.getOrDefault("DRAFT", 0L));
        postSummary.put("hidden", postCounts.getOrDefault("HIDDEN", 0L));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> waiting = (List<Map<String, Object>>) state.get("waiting");
        Map<String, Object> queueSummary = new LinkedHashMap<>();
        queueSummary.put("inRoomCount", state.get("inRoomCount"));
        queueSummary.put("waitingCount", state.get("waitingCount"));
        queueSummary.put("examiningCount", ((List<?>) state.get("examining")).size());
        queueSummary.put("treatmentWaitingCount", state.get("treatmentWaitingCount"));
        queueSummary.put("awaitingConclusionCount", ((List<?>) state.get("awaitingConclusion")).size());
        queueSummary.put("completedCount", state.get("completedCount"));
        queueSummary.put("capacity", state.get("capacity"));
        queueSummary.put("slotMap", state.get("slotMap"));
        queueSummary.put("waiting", waiting.stream().map(w -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ticketCode", w.get("ticketCode"));
            item.put("priorityGroup", w.get("priorityGroup"));
            return item;
        }).toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("appointments", appointments);
        out.put("patients", Map.of("total", patients));
        out.put("posts", postSummary);
        out.put("accounts", accounts);
        out.put("drafts", posts.findTop5ByStatusOrderByUpdatedAtDescIdDesc("DRAFT").stream()
                .map(p -> postService.postView(p, false)).toList());
        out.put("queue", queueSummary);
        return out;
    }
}
