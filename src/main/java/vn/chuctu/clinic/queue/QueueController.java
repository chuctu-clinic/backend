package vn.chuctu.clinic.queue;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
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

/**
 * Check-in (tiếp tân), hàng đợi (mọi nhân viên xem), khám sơ bộ (bác sĩ) và chỗ điều trị (kỹ thuật viên). Quyền theo
 * đường dẫn do SecurityConfig: /exam/** cho Bác sĩ, /treatment/** và /queue/auto-invite cho Kỹ thuật viên, Quản trị viên
 * làm được tất cả.
 */
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

    // ---------- Tiếp tân ----------

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

    @GetMapping("/queue")
    Map<String, Object> queue() {
        return state();
    }

    // ---------- Bác sĩ ----------

    @PostMapping("/exam/call-next")
    Map<String, Object> callNext() {
        long id = queue.callNext(currentUser.require(), today(), clock.instant());
        Map<String, Object> out = new java.util.LinkedHashMap<>(state());
        out.put("calledId", String.valueOf(id));
        return out;
    }

    @PostMapping("/exam/{checkinId}/call")
    Map<String, Object> call(@PathVariable long checkinId) {
        queue.call(currentUser.require(), checkinId, clock.instant());
        return state();
    }

    @PostMapping("/exam/{checkinId}/undo-call")
    Map<String, Object> undoCall(@PathVariable long checkinId) {
        queue.undoCall(currentUser.require(), checkinId);
        return state();
    }

    @PostMapping("/exam/{checkinId}/order")
    Map<String, Object> order(@PathVariable long checkinId, @RequestBody(required = false) JsonNode json) {
        var data = VisitForms.parseOrder(json);
        queue.orderTreatment(currentUser.require(), checkinId, data);
        return state();
    }

    @PostMapping("/exam/{checkinId}/undo-order")
    Map<String, Object> undoOrder(@PathVariable long checkinId) {
        queue.undoOrder(currentUser.require(), checkinId);
        return state();
    }

    // ---------- Kỹ thuật viên ----------

    @PostMapping("/treatment/invite")
    Map<String, Object> invite() {
        queue.inviteNext(today(), clock.instant(), currentUser.require());
        return state();
    }

    @PostMapping("/treatment/{checkinId}/seat")
    Map<String, Object> seat(@PathVariable long checkinId) {
        queue.seat(checkinId, clock.instant(), currentUser.require());
        return state();
    }

    @PostMapping("/treatment/{checkinId}/undo-seat")
    Map<String, Object> undoSeat(@PathVariable long checkinId) {
        queue.undoEnter(checkinId);
        return state();
    }

    @PostMapping("/treatment/{checkinId}/report")
    Map<String, Object> report(@PathVariable long checkinId, @RequestBody(required = false) JsonNode json) {
        var data = VisitForms.parseReport(json);
        queue.finishTreatment(currentUser.require(), checkinId, data);
        return state();
    }

    @PutMapping("/queue/auto-invite")
    Map<String, Object> autoInvite(@RequestBody(required = false) JsonNode json) {
        Body body = Body.of(json);
        boolean enabled = body.requiredBoolean("enabled");
        body.check();
        queue.setAutoInvite(enabled);
        if (enabled) queue.fillSeatsAfterCommit(today(), List.of());
        return state();
    }
}
