package vn.chuctu.clinic.appointment;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.security.CurrentUser;

/** Đặt lịch và phiếu khám của người dùng (UC.6.1 đến UC.6.9), phần phiếu khám của routes/me.routes.js. Quyền USER do SecurityConfig. */
@RestController
@RequestMapping("/api/me")
public class MeAppointmentController {

    private final AppointmentService appointments;
    private final SlotService slots;
    private final CurrentUser currentUser;
    private final Clock clock;

    public MeAppointmentController(AppointmentService appointments, SlotService slots, CurrentUser currentUser, Clock clock) {
        this.appointments = appointments;
        this.slots = slots;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping("/availability")
    Map<String, Object> availability(@RequestParam(required = false) String date) {
        LocalDate day = AppointmentForms.dateParam(date);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("slots", slots.availability(day, SlotService.USER, clock.instant()));
        return out;
    }

    @PostMapping("/appointments")
    ResponseEntity<Map<String, Object>> book(@RequestBody(required = false) JsonNode json) {
        User user = currentUser.require();
        var request = AppointmentForms.userBooking(Body.of(json));
        Appointment appt = appointments.userBook(user, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("appointment", appointments.getUserAppointment(user, appt.getId())));
    }

    @GetMapping("/appointments")
    Map<String, Object> list(@RequestParam(required = false) String status) {
        List<Map<String, Object>> items = appointments.listUserAppointments(currentUser.require(), status);
        return Map.of("items", items);
    }

    @GetMapping("/appointments/{id}")
    Map<String, Object> get(@PathVariable long id) {
        return Map.of("appointment", appointments.getUserAppointment(currentUser.require(), id));
    }

    @PostMapping("/appointments/{id}/cancel")
    Map<String, Object> cancel(@PathVariable long id, @RequestBody(required = false) JsonNode json) {
        User user = currentUser.require();
        String reason = AppointmentForms.userCancelReason(Body.of(json));
        appointments.userCancel(user, id, reason);
        return Map.of("appointment", appointments.getUserAppointment(user, id));
    }
}
