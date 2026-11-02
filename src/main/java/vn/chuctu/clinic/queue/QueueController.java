package vn.chuctu.clinic.queue;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.appointment.AppointmentService;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.security.CurrentUser;

/** Check-in, vào phòng khám và hàng đợi (UC.7.3, UC.7.4, UC.10.1 đến UC.10.5), phần hàng đợi của routes/staff.routes.js. */
@RestController
@RequestMapping("/api/staff")
public class QueueController {

    private final QueueService queue;
    private final AppointmentService appointments;
    private final CurrentUser currentUser;
    private final Clock clock;

    public QueueController(QueueService queue, AppointmentService appointments, CurrentUser currentUser, Clock clock) {
        this.queue = queue;
        this.appointments = appointments;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    private LocalDate today() {
        return VnTime.today(clock.instant());
    }

    private Map<String, Object> state() {
        return queue.queueState(today(), clock.instant());
    }

    private Map<String, Object> appointment(long id) {
        return Map.of("appointment", appointments.getStaffAppointment(id));
    }

    @PostMapping("/appointments/{id}/checkin")
    Map<String, Object> checkIn(@PathVariable long id) {
        queue.checkIn(currentUser.require(), id);
        return appointment(id);
    }

    @PostMapping("/appointments/{id}/undo-checkin")
    Map<String, Object> undoCheckIn(@PathVariable long id) {
        queue.undoCheckIn(id);
        return appointment(id);
    }

    @PostMapping("/appointments/{id}/enter")
    Map<String, Object> enter(@PathVariable long id) {
        queue.enterRoom(id, clock.instant());
        return appointment(id);
    }

    @PostMapping("/appointments/{id}/undo-enter")
    Map<String, Object> undoEnter(@PathVariable long id) {
        queue.undoEnterByAppointment(id);
        return appointment(id);
    }

    @GetMapping("/queue")
    Map<String, Object> queue() {
        return state();
    }

    @PostMapping("/queue/invite")
    Map<String, Object> invite() {
        queue.inviteNext(today(), clock.instant());
        return state();
    }

    @PostMapping("/queue/{checkinId}/undo-enter")
    Map<String, Object> undoEnterByCheckin(@PathVariable long checkinId) {
        queue.undoEnter(checkinId);
        return state();
    }

    @PutMapping("/queue/auto-invite")
    Map<String, Object> autoInvite(@RequestBody(required = false) JsonNode json) {
        Body body = Body.of(json);
        boolean enabled = body.requiredBoolean("enabled");
        body.check();
        queue.setAutoInvite(enabled);
        if (enabled) queue.fillSeats(today());
        return state();
    }
}
