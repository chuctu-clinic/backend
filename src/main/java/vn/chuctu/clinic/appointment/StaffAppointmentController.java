package vn.chuctu.clinic.appointment;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.queue.QueueService;
import vn.chuctu.clinic.security.CurrentUser;

/**
 * Lịch trong ngày, đặt hộ, đổi giờ và huỷ tại quầy (UC.7.1, UC.7.2, UC.7.5 đến UC.7.7), phần phiếu khám của
 * routes/staff.routes.js. Check-in và vào phòng nằm ở QueueController; bệnh án (Task 9) nối vào chỗ ghi chú bên dưới.
 */
@RestController
@RequestMapping("/api/staff")
public class StaffAppointmentController {

    private final AppointmentService appointments;
    private final SlotService slots;
    private final QueueService queue;
    private final CurrentUser currentUser;
    private final Clock clock;

    public StaffAppointmentController(AppointmentService appointments, SlotService slots, QueueService queue, CurrentUser currentUser,
            Clock clock) {
        this.appointments = appointments;
        this.queue = queue;
        this.slots = slots;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping("/appointments")
    Map<String, Object> listDay(@RequestParam(required = false) String date, @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        LocalDate day = AppointmentForms.dateParam(date == null || date.isEmpty() ? VnTime.today(clock.instant()).toString() : date);
        AppointmentService.DayView view = appointments.listDay(day, status, q);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        // Mỗi phiếu kèm check-in của nó (null nếu chưa check-in hoặc đã hoàn tác)
        Map<Long, Map<String, Object>> checkins =
                queue.checkinsByAppointment(view.items().stream().map(item -> Long.parseLong((String) item.get("id"))).toList());
        view.items().forEach(item -> item.put("checkin", checkins.get(Long.parseLong((String) item.get("id")))));
        out.put("items", view.items());
        out.put("summary", view.summary());
        return out;
    }

    @GetMapping("/appointments/dates")
    Map<String, Object> dates(@RequestParam(required = false) String month) {
        var parsed = AppointmentForms.monthParam(month);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", parsed.toString());
        out.put("dates", appointments.datesInMonth(parsed));
        return out;
    }

    @GetMapping("/appointments/lookup")
    Map<String, Object> lookup(@RequestParam(required = false) String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appointment", appointments.findByTicketCode(AppointmentForms.codeParam(code)));
        return out;
    }

    @GetMapping("/appointments/{id}")
    Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appointment", appointments.getStaffAppointment(id));
        // Task 9: bệnh án của phiếu (records.getRecordByAppointment); đến lúc đó chưa có bệnh án nào
        out.put("record", null);
        return out;
    }

    @GetMapping("/availability")
    Map<String, Object> availability(@RequestParam(required = false) String date) {
        LocalDate day = AppointmentForms.dateParam(date);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("slots", slots.availability(day, SlotService.COUNTER, clock.instant()));
        return out;
    }

    @PostMapping("/appointments")
    ResponseEntity<Map<String, Object>> book(@RequestBody(required = false) JsonNode json) {
        User staff = currentUser.require();
        Body body = Body.of(json);
        var form = AppointmentForms.counterBooking(body);
        // Ngoài giờ nhận bệnh thì báo trước, không tạo phiếu rồi mới lỗi ở bước check-in
        if (form.checkInNow()) queue.assertReceivingTime();
        Appointment appt = appointments.counterBook(staff, form.request());
        if (form.checkInNow()) queue.checkIn(staff, appt.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("appointment", appointments.getStaffAppointment(appt.getId())));
    }

    @PostMapping("/appointments/{id}/reschedule")
    Map<String, Object> reschedule(@PathVariable long id, @RequestBody(required = false) JsonNode json) {
        var slot = AppointmentForms.reschedule(Body.of(json));
        Appointment appt = appointments.reschedule(currentUser.require(), id, slot.date(), slot.start());
        return Map.of("appointment", appointments.getStaffAppointment(appt.getId()));
    }

    @PostMapping("/appointments/{id}/cancel")
    Map<String, Object> cancel(@PathVariable long id, @RequestBody(required = false) JsonNode json) {
        User staff = currentUser.require();
        String reason = AppointmentForms.counterCancelReason(Body.of(json));
        appointments.counterCancel(staff, id, reason);
        return Map.of("appointment", appointments.getStaffAppointment(id));
    }
}
