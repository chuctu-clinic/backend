package vn.chuctu.clinic.appointment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.chuctu.clinic.common.BusinessClock;

/**
 * Tự huỷ phiếu không đến (cancelNoShows của index.js): chạy ngay khi khởi động để huỷ bù phiếu bị bỏ sót lúc server
 * tắt, rồi mỗi phút một lần. Test đặt clinic.no-show-job.enabled=false nên bean này không tồn tại và không chạy.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "clinic.no-show-job.enabled", havingValue = "true", matchIfMissing = true)
public class NoShowJob {

    private static final Logger log = LoggerFactory.getLogger(NoShowJob.class);

    private final AppointmentService appointments;
    private final BusinessClock businessClock;

    public NoShowJob(AppointmentService appointments, BusinessClock businessClock) {
        this.appointments = appointments;
        this.businessClock = businessClock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        run();
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void run() {
        try {
            int n = appointments.autoCancelNoShows(businessClock.now());
            if (n > 0) log.info("[Tự huỷ] {} phiếu khám không đến đã chuyển sang Đã huỷ.", n);
        } catch (RuntimeException e) {
            log.error("[Tự huỷ] Lỗi: {}", e.getMessage());
        }
    }
}
