package vn.chuctu.clinic.dashboard;

import java.time.Clock;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.chuctu.clinic.common.VnTime;

/** Tổng quan của Admin (UC.2.3), đầu routes/admin.routes.js. Quyền ADMIN do SecurityConfig. */
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final DashboardService dashboard;
    private final Clock clock;

    public AdminDashboardController(DashboardService dashboard, Clock clock) {
        this.dashboard = dashboard;
        this.clock = clock;
    }

    @GetMapping
    Map<String, Object> overview() {
        var now = clock.instant();
        return dashboard.overview(VnTime.today(now), now);
    }
}
