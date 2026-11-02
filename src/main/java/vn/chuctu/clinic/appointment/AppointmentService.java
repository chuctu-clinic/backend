package vn.chuctu.clinic.appointment;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Constants;
import vn.chuctu.clinic.common.Counters;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Likes;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.patient.Patient;
import vn.chuctu.clinic.patient.PatientRepository;
import vn.chuctu.clinic.patient.PatientService;

/**
 * Đặt lịch, huỷ, đổi giờ và xem phiếu khám (services/appointment.service.js). Mỗi thao tác ghi chạy trong một giao dịch
 * để chỗ trong khung giờ, phiếu và mã phiếu cùng thành công hoặc cùng quay lui (thay cho code bù trừ của Node). Gặp
 * deadlock của MySQL (lỗi 1213) thì làm lại cả giao dịch, tối đa 3 lần: vòng thử lại nằm ngoài giao dịch nên mỗi lần
 * thử là một giao dịch mới.
 */
@Service
public class AppointmentService {

    public static final List<String> COUNTER_CANCEL_REASONS = List.of("Bệnh nhân xin huỷ", "Không đến được", "Đặt nhầm", "Khác");
    public static final String NO_SHOW_REASON =
            "Không đến khám (hệ thống tự huỷ lúc " + Constants.RECEIVING_HOURS.noShowCancel().replaceFirst("^0", "") + ")";

    private static final int MAX_ATTEMPTS = 3;
    private static final int MYSQL_DEADLOCK = 1213;
    private static final int LIST_LIMIT = 200;
    private static final String ACTIVE_LIST = "'" + String.join("','", Constants.ACTIVE_STATUSES) + "'";
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Dữ liệu một lần đặt lịch (đã kiểm tra dạng ở tầng controller). */
    public record BookingRequest(long patientId, String serviceType, LocalDate slotDate, String slotStart, Long rebookFromId) {}

    /** Lịch trong ngày: danh sách phiếu và số phiếu theo từng trạng thái. */
    public record DayView(List<Map<String, Object>> items, Map<String, Integer> summary) {}

    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final SlotService slots;
    private final Counters counters;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AppointmentService(AppointmentRepository appointments, PatientRepository patients, SlotService slots, Counters counters,
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.appointments = appointments;
        this.patients = patients;
        this.slots = slots;
        this.counters = counters;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Người dùng chỉ thấy ba trạng thái: Đã đặt lịch, Đã hoàn thành, Đã huỷ (BR.17). */
    public static String userStatus(String status) {
        if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) return status;
        return "BOOKED";
    }

    // ---------- Giao dịch có thử lại ----------

    /** Chạy trong một giao dịch mới, làm lại khi MySQL báo deadlock. */
    private <T> T inTransaction(Supplier<T> work) {
        for (int attempt = 1;; attempt++) {
            try {
                return tx.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (attempt >= MAX_ATTEMPTS || !isDeadlock(e)) throw e;
            }
        }
    }

    private static boolean isDeadlock(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == MYSQL_DEADLOCK) return true;
        }
        return false;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    // ---------- Tạo phiếu ----------

    private String nextTicketCode(LocalDate date) {
        long seq = counters.next("ticket-" + date);
        return "CT-PK-" + VnTime.yy(date) + "-" + VnTime.ddmm(date) + "-" + String.format("%03d", seq);
    }

    private Appointment createAppointment(Patient patient, String serviceType, LocalDate date, String start, String source, Long userId,
            Long createdBy, Long rescheduledFromId, String mode) {
        if (!Constants.SERVICE_TYPES.containsKey(serviceType)) {
            throw Errors.badRequest("Nội dung khám không hợp lệ.", Map.of("fields", Map.of("serviceType", "Cô chú vui lòng chọn nội dung khám.")),
                    "BAD_REQUEST");
        }
        if (patient.isLocked()) {
            throw Errors.forbidden("Hồ sơ này đang bị phòng khám khoá nên không đặt lịch được. Cô chú gọi phòng khám để được hỗ trợ.");
        }
        String issue = slots.timeIssue(date, start, mode, clock.instant());
        if (issue != null) throw Errors.badRequest(issue, Map.of("fields", Map.of("slotStart", issue)), "SLOT_UNAVAILABLE");

        // Kiểm tra này không được bảo vệ khi đặt đồng thời, giống Node (xem spec "Các thao tác đồng thời")
        if (appointments.existsByPatientIdAndSlotDateAndStatusIn(patient.getId(), date, Constants.ACTIVE_STATUSES)) {
            throw Errors.conflict(patient.getFullName() + " đã có phiếu khám trong ngày này. Cô chú xem lại mục Phiếu khám bệnh.",
                    "ALREADY_BOOKED");
        }
        if (!slots.reserve(date, start)) {
            throw Errors.conflict("Khung giờ này vừa đủ chỗ. Cô chú vui lòng chọn khung giờ khác.", "SLOT_FULL");
        }
        Appointment appt = new Appointment();
        appt.setTicketCode(nextTicketCode(date));
        appt.setUserId(userId);
        appt.setPatientId(patient.getId());
        appt.setServiceType(serviceType);
        appt.setSlotDate(date);
        appt.setSlotStart(start);
        appt.setExpectedTime(VnTime.slotInstant(date, start));
        appt.setStatus("BOOKED");
        appt.setSource(source);
        appt.setCreatedBy(createdBy);
        appt.setRescheduledFromId(rescheduledFromId);
        return appointments.saveAndFlush(appt);
    }

    /** Người dùng đặt lịch cho hồ sơ thuộc tài khoản của mình (UC.6.3). */
    public Appointment userBook(User user, BookingRequest req) {
        return inTransaction(() -> {
            Patient patient = patients.findByIdAndUserId(req.patientId(), user.getId())
                    .orElseThrow(() -> Errors.notFound("Không tìm thấy hồ sơ người đi khám."));
            if (patient.getRelationship() == null || patient.getRelationship().isEmpty()) {
                throw Errors.badRequest("Cô chú vui lòng khai quan hệ với người đi khám trong hồ sơ.",
                        Map.of("fields", Map.of("relationship", "Chưa khai quan hệ.")), "BAD_REQUEST");
            }
            if ("BAN_THAN".equals(patient.getRelationship())
                    && (patient.getDob() == null || isBlank(patient.getGender()) || isBlank(patient.getAddress()))) {
                throw Errors.badRequest("Hồ sơ của cô chú còn thiếu thông tin. Cô chú bổ sung ngày sinh, giới tính và địa chỉ rồi đặt lịch.",
                        null, "PROFILE_INCOMPLETE");
            }
            Long rescheduledFromId = null;
            if (req.rebookFromId() != null) {
                rescheduledFromId = appointments.findByIdAndUserIdAndStatus(req.rebookFromId(), user.getId(), "CANCELLED")
                        .map(Appointment::getId).orElse(null);
            }
            return createAppointment(patient, req.serviceType(), req.slotDate(), req.slotStart(), "ONLINE", user.getId(), user.getId(),
                    rescheduledFromId, SlotService.USER);
        });
    }

    /** Tiếp tân đặt hộ tại quầy (UC.7.5). */
    public Appointment counterBook(User staff, BookingRequest req) {
        return inTransaction(() -> {
            Patient patient = patients.findById(req.patientId()).orElseThrow(() -> Errors.notFound("Không tìm thấy hồ sơ bệnh nhân."));
            return createAppointment(patient, req.serviceType(), req.slotDate(), req.slotStart(), "COUNTER", patient.getUserId(),
                    staff.getId(), null, SlotService.COUNTER);
        });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    // ---------- Huỷ, đổi giờ ----------

    /**
     * Huỷ một phiếu còn hiệu lực và nhả chỗ. Chỉ nhả chỗ khi UPDATE có điều kiện đổi đúng một dòng: hai bên cùng huỷ
     * (người dùng, tiếp tân hay tự huỷ) thì chỉ một bên nhả. Phải gọi trong giao dịch.
     */
    private void cancelInternal(Appointment appt, User by, String reason) {
        if ("COMPLETED".equals(appt.getStatus())) throw Errors.conflict("Phiếu khám đã hoàn thành nên không huỷ được.", "NOT_CANCELLABLE");
        if ("CANCELLED".equals(appt.getStatus())) throw Errors.conflict("Phiếu khám này đã được huỷ trước đó.", "NOT_CANCELLABLE");
        LocalDateTime now = utc(clock.instant());
        int changed = jdbc.update("UPDATE appointments SET status = 'CANCELLED', cancel_reason = ?, cancelled_by = ?, cancelled_at = ?, "
                + "updated_at = ? WHERE id = ? AND status IN (" + ACTIVE_LIST + ")", isBlank(reason) ? null : reason, by.getId(), now, now,
                appt.getId());
        if (changed != 1) throw Errors.conflict("Phiếu khám vừa thay đổi trạng thái, cô chú tải lại trang.", "STALE");
        // Người đã check-in mà huỷ phiếu thì rời hàng đợi (Task 8 thay bằng QueueService.leaveQueue)
        jdbc.update("UPDATE checkins SET status = 'REVERTED', seat_no = NULL WHERE appointment_id = ? AND status IN ('WAITING', 'IN_ROOM')",
                appt.getId());
        slots.release(appt.getSlotDate(), appt.getSlotStart());
    }

    /**
     * Tự huỷ phiếu không đến: từ 17:00 phiếu hôm nay còn Chưa đến chuyển sang Đã huỷ; phiếu Chưa đến của các ngày trước
     * (bị bỏ sót khi server tắt) huỷ bù ngay. Phiếu đã check-in giữ nguyên. Trả về số phiếu đã huỷ.
     */
    public int autoCancelNoShows(Instant now) {
        LocalDate today = VnTime.today(now);
        boolean pastCutoff = VnTime.minutesOfDay(now) >= VnTime.hhmmToMinutes(Constants.RECEIVING_HOURS.noShowCancel());
        List<Map<String, Object>> due = jdbc.queryForList("SELECT id, slot_date, slot_start FROM appointments WHERE status = 'BOOKED' "
                + "AND slot_date " + (pastCutoff ? "<=" : "<") + " ?", today);
        int cancelled = 0;
        for (Map<String, Object> row : due) {
            long id = ((Number) row.get("id")).longValue();
            LocalDate date = ((java.sql.Date) row.get("slot_date")).toLocalDate();
            String start = (String) row.get("slot_start");
            boolean changed = inTransaction(() -> {
                int n = jdbc.update("UPDATE appointments SET status = 'CANCELLED', cancel_reason = ?, cancelled_by = NULL, cancelled_at = ?, "
                        + "updated_at = ? WHERE id = ? AND status = 'BOOKED'", NO_SHOW_REASON, utc(now), utc(clock.instant()), id);
                if (n == 1) slots.release(date, start);
                return n == 1;
            });
            if (changed) cancelled++;
        }
        return cancelled;
    }

    public void userCancel(User user, long appointmentId, String reason) {
        inTransaction(() -> {
            Appointment appt = appointments.findByIdAndUserId(appointmentId, user.getId())
                    .orElseThrow(() -> Errors.notFound("Không tìm thấy phiếu khám."));
            cancelInternal(appt, user, reason);
            return null;
        });
    }

    public void counterCancel(User staff, long appointmentId, String reason) {
        if (isBlank(reason)) {
            throw Errors.badRequest("Vui lòng chọn lý do huỷ.", Map.of("fields", Map.of("reason", "Vui lòng chọn lý do huỷ.")), "BAD_REQUEST");
        }
        inTransaction(() -> {
            Appointment appt = appointments.findById(appointmentId).orElseThrow(() -> Errors.notFound("Không tìm thấy phiếu khám."));
            cancelInternal(appt, staff, reason);
            return null;
        });
    }

    /** Đổi giờ tính như đặt mới vào khung mới: phiếu cũ huỷ, phiếu mới ghi rescheduledFromId (BR.26). Trả về phiếu mới. */
    public Appointment reschedule(User staff, long appointmentId, LocalDate date, String start) {
        return inTransaction(() -> {
            Appointment old = appointments.findById(appointmentId).orElseThrow(() -> Errors.notFound("Không tìm thấy phiếu khám."));
            if (!"BOOKED".equals(old.getStatus())) throw Errors.conflict("Chỉ đổi giờ được phiếu chưa check-in.", "NOT_RESCHEDULABLE");
            if (old.getSlotDate().equals(date) && old.getSlotStart().equals(start)) {
                throw Errors.badRequest("Khung giờ mới trùng khung giờ cũ.");
            }
            Patient patient = patients.findById(old.getPatientId()).orElseThrow(() -> Errors.notFound("Không tìm thấy hồ sơ bệnh nhân."));
            String issue = slots.timeIssue(date, start, SlotService.COUNTER, clock.instant());
            if (issue != null) throw Errors.badRequest(issue, Map.of("fields", Map.of("slotStart", issue)), "SLOT_UNAVAILABLE");
            if (patient.isLocked()) throw Errors.forbidden("Hồ sơ đang bị khoá nên không đặt lịch được.");

            // Giữ chỗ khung mới trước, rồi mới huỷ phiếu cũ: khung mới đã đầy thì giao dịch quay lui, phiếu cũ còn nguyên
            if (!slots.reserve(date, start)) {
                throw Errors.conflict("Khung giờ này vừa đủ chỗ. Cô chú vui lòng chọn khung giờ khác.", "SLOT_FULL");
            }
            Appointment created = new Appointment();
            created.setTicketCode(nextTicketCode(date));
            created.setUserId(old.getUserId());
            created.setPatientId(old.getPatientId());
            created.setServiceType(old.getServiceType());
            created.setSlotDate(date);
            created.setSlotStart(start);
            created.setExpectedTime(VnTime.slotInstant(date, start));
            created.setStatus("BOOKED");
            created.setSource(old.getSource());
            created.setCreatedBy(staff.getId());
            created.setRescheduledFromId(old.getId());
            created = appointments.saveAndFlush(created);
            cancelInternal(old, staff, "Đổi giờ sang " + start + " ngày " + DMY.format(date));
            return created;
        });
    }

    // ---------- Xem phiếu ----------

    public Map<String, Object> appointmentView(Appointment appt, Patient patient, boolean forUser) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(appt.getId()));
        out.put("ticketCode", appt.getTicketCode());
        out.put("serviceType", appt.getServiceType());
        out.put("serviceLabel", Constants.SERVICE_TYPES.get(appt.getServiceType()));
        out.put("slotDate", appt.getSlotDate().toString());
        out.put("slotStart", appt.getSlotStart());
        out.put("expectedTime", appt.getExpectedTime());
        out.put("status", forUser ? userStatus(appt.getStatus()) : appt.getStatus());
        out.put("source", appt.getSource());
        out.put("cancelReason", appt.getCancelReason());
        out.put("cancelledAt", appt.getCancelledAt());
        out.put("rescheduledFromId", appt.getRescheduledFromId() == null ? null : String.valueOf(appt.getRescheduledFromId()));
        out.put("createdAt", appt.getCreatedAt());
        out.put("patient", patient == null ? null : patientView(patient, forUser, false));
        return out;
    }

    /** Người dùng không thấy số điện thoại trong danh sách; phiếu đầy đủ để in thì kèm ngày sinh, giới tính, địa chỉ. */
    private static Map<String, Object> patientView(Patient patient, boolean forUser, boolean full) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(patient.getId()));
        out.put("patientCode", patient.getPatientCode());
        out.put("fullName", patient.getFullName());
        out.put("relationship", patient.getRelationship());
        out.put("birthYear", patient.getBirthYear() != null ? patient.getBirthYear()
                : patient.getDob() != null ? Integer.valueOf(patient.getDob().getYear()) : null);
        if (!forUser || full) out.put("phone", patient.getPhone());
        out.put("isLocked", patient.isLocked());
        if (full) {
            out.put("dob", PatientService.midnightUtc(patient.getDob()));
            out.put("gender", patient.getGender());
            out.put("address", patient.getAddress());
        }
        return out;
    }

    private List<Map<String, Object>> withPatients(List<Appointment> list, boolean forUser) {
        Set<Long> ids = new HashSet<>();
        for (Appointment a : list) ids.add(a.getPatientId());
        Map<Long, Patient> byId = new HashMap<>();
        for (Patient p : patients.findAllById(ids)) byId.put(p.getId(), p);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Appointment a : list) out.add(appointmentView(a, byId.get(a.getPatientId()), forUser));
        return out;
    }

    /** Các phiếu đã có bệnh án. Bảng medical_records do Task 9 quản lý nên ở đây chỉ đọc cột appointment_id. */
    private Set<Long> appointmentsWithResult(Collection<Long> appointmentIds) {
        Set<Long> out = new HashSet<>();
        if (appointmentIds.isEmpty()) return out;
        String marks = String.join(",", appointmentIds.stream().map(id -> "?").toList());
        jdbc.query("SELECT appointment_id FROM medical_records WHERE appointment_id IN (" + marks + ")",
                rs -> {
                    out.add(rs.getLong(1));
                }, appointmentIds.toArray());
        return out;
    }

    /** Phiếu khám của mọi hồ sơ thuộc tài khoản (BR.20). status: BOOKED (gồm cả đã check-in, đang khám), COMPLETED, CANCELLED. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUserAppointments(User user, String status) {
        Collection<String> statuses = "BOOKED".equals(status) ? Constants.ACTIVE_STATUSES
                : "COMPLETED".equals(status) || "CANCELLED".equals(status) ? List.of(status) : Constants.APPOINTMENT_STATUSES;
        List<Appointment> list = appointments.listOwned(user.getId(), statuses);
        List<Map<String, Object>> items = withPatients(list, true);
        Set<Long> withResult = appointmentsWithResult(list.stream().map(Appointment::getId).toList());
        for (int i = 0; i < items.size(); i++) items.get(i).put("hasResult", withResult.contains(list.get(i).getId()));
        return items;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUserAppointment(User user, long appointmentId) {
        Appointment appt = appointments.findOwned(appointmentId, user.getId())
                .orElseThrow(() -> Errors.notFound("Không tìm thấy phiếu khám."));
        Patient patient = patients.findById(appt.getPatientId()).orElse(null);
        Map<String, Object> view = appointmentView(appt, patient, true);
        // Phiếu khám đầy đủ in ra cho người đặt: kèm ngày sinh, số điện thoại, địa chỉ người đi khám
        if (patient != null) view.put("patient", patientView(patient, true, true));
        view.put("hasResult", !appointmentsWithResult(List.of(appt.getId())).isEmpty());
        return view;
    }

    /** Lịch trong ngày cho Tiếp tân: lọc theo trạng thái, tra cứu mã phiếu hoặc tên (UC.7.1, UC.7.2). */
    @Transactional(readOnly = true)
    public DayView listDay(LocalDate date, String status, String q) {
        String pattern = q == null || q.isEmpty() ? null : Likes.contains(Body.jsTrim(q));
        String statusFilter = status != null && Constants.STATUS_LABELS.containsKey(status) ? status : null;
        List<Map<String, Object>> items = withPatients(appointments.listDay(date, statusFilter, pattern), false);

        Map<String, Integer> summary = new LinkedHashMap<>();
        for (String s : Constants.STATUS_LABELS.keySet()) summary.put(s, 0);
        for (Object[] row : appointments.countByStatusOnDate(date)) summary.put((String) row[0], ((Number) row[1]).intValue());
        return new DayView(items, summary);
    }

    /** Các ngày trong tháng có phiếu khám chưa huỷ, để lịch chọn ngày đánh dấu. month dạng YYYY-MM. */
    @Transactional(readOnly = true)
    public List<String> datesInMonth(YearMonth month) {
        return appointments.datesWithTickets(month.atDay(1), month.plusMonths(1).atDay(1)).stream().map(LocalDate::toString).toList();
    }

    /** Tra cứu mã phiếu ở mọi ngày; null nếu không có. */
    @Transactional(readOnly = true)
    public Map<String, Object> findByTicketCode(String code) {
        Appointment appt = appointments.findByTicketCode(Body.jsTrim(code).toUpperCase(java.util.Locale.ROOT)).orElse(null);
        if (appt == null) return null;
        return appointmentView(appt, patients.findById(appt.getPatientId()).orElse(null), false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStaffAppointment(long appointmentId) {
        Appointment appt = appointments.findById(appointmentId).orElseThrow(() -> Errors.notFound("Không tìm thấy phiếu khám."));
        return appointmentView(appt, patients.findById(appt.getPatientId()).orElse(null), false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPatientAppointments(long patientId) {
        return withPatients(appointments.findTop200ByPatientIdOrderBySlotDateDescSlotStartDescIdDesc(patientId), false);
    }
}
