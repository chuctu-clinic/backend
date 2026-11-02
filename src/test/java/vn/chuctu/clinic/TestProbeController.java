package vn.chuctu.clinic;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.security.CurrentUser;

/**
 * Đường dẫn thử cho kiểm thử hạ tầng khi các controller thật chưa có. Dùng tiền tố _probe để không đụng đường dẫn
 * thật của các task sau.
 */
@RestController
public class TestProbeController {

    private final CurrentUser currentUser;

    public TestProbeController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping("/api/me/_probe/appointments/{id}")
    Map<String, Object> appointment(@PathVariable long id) {
        return Map.of("id", String.valueOf(id));
    }

    @GetMapping("/api/auth/_probe/require")
    Map<String, Object> authRequired() {
        return who(currentUser.require());
    }

    @GetMapping("/api/me/_probe")
    Map<String, Object> me() {
        return who(currentUser.require());
    }

    @GetMapping("/api/staff/_probe")
    Map<String, Object> staff() {
        return who(currentUser.require());
    }

    @GetMapping("/api/admin/_probe")
    Map<String, Object> admin() {
        return who(currentUser.require());
    }

    @GetMapping("/api/auth/_probe/instants")
    Map<String, Object> instants() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("whole", Instant.parse("2026-11-02T03:00:00Z"));
        out.put("tenths", Instant.parse("2026-11-02T03:00:00.120Z"));
        out.put("millis", Instant.parse("2026-11-02T03:00:00.123Z"));
        return out;
    }

    private static Map<String, Object> who(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user == null ? null : String.valueOf(user.getId()));
        out.put("role", user == null ? null : user.getRole());
        return out;
    }
}
