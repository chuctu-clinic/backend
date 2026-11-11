package vn.chuctu.clinic.queue;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;
import vn.chuctu.clinic.appointment.Appointment;
import vn.chuctu.clinic.appointment.AppointmentRepository;
import vn.chuctu.clinic.common.BusinessClock;
import vn.chuctu.clinic.common.Constants;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Rules;
import vn.chuctu.clinic.common.Tx;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.patient.Patient;
import vn.chuctu.clinic.patient.PatientRepository;
import vn.chuctu.clinic.queue.VisitForms.OrderData;
import vn.chuctu.clinic.queue.VisitForms.ReportData;

/**
 * Check-in, hàng chờ khám, khám sơ bộ, chỗ điều trị và chờ kết luận.
 *
 * <p>Quy trình của một lượt check-in: WAITING (chờ bác sĩ) → EXAMINING (bác sĩ gọi vào khám sơ bộ) → WAITING_TREATMENT (bác sĩ
 * chỉ định điều trị) → IN_ROOM (ngồi một trong 5 chỗ điều trị) → WAITING_CONCLUSION (kỹ thuật viên lưu báo cáo, chỗ trả lại)
 * → COMPLETED (bác sĩ lưu phiếu kết quả). Bác sĩ có thể kết luận thẳng từ EXAMINING khi không cần điều trị. Phiếu khám ở
 * CHECKED_IN khi check-in còn WAITING và ở IN_PROGRESS từ lúc bác sĩ gọi đến khi có phiếu kết quả.
 *
 * <p>Đồng hồ: {@link #checkIn}, {@link #isReceivingTime} và {@link #assertReceivingTime} dùng {@link BusinessClock} (chỗ Node
 * dùng clock.now); {@link #inviteNext}, {@link #enterRoom}, {@link #fillSeats}, {@link #markCheckinCompleted} và các giờ ghi
 * vào database dùng {@link Clock} (chỗ Node dùng new Date()).
 *
 * <p>Một chỗ không bao giờ có hai người nhờ ràng buộc UNIQUE (visit_date, in_room_seat_no) của cột sinh tự động. Mỗi lần thử
 * một chỗ ({@link #seatOne}) chạy trong giao dịch riêng, để lỗi trùng khoá chỉ quay lui lần thử đó chứ không làm hỏng
 * giao dịch hay session của nơi gọi. Vì vậy {@link #fillSeats}, {@link #inviteNext}, {@link #enterRoom} và
 * {@link #fillAfterCompletion} phải được gọi ngoài giao dịch (sau khi giao dịch trước đó đã commit):
 * giao dịch riêng không thấy thay đổi chưa commit của nơi gọi. Riêng {@link #markCheckinCompleted} chỉ là một câu UPDATE và
 * chạy trong giao dịch của nơi gọi.
 *
 * <p>Thứ tự khoá cố định là phiếu khám trước, check-in sau (cùng thứ tự với huỷ phiếu). Gặp deadlock thì làm lại.
 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    public static final String AUTO_KEY = "queue.autoInvite";

    private static final int SEATS = Rules.QUEUE_SEATS;
    /** Các trạng thái chưa xong của một lượt (còn trong phòng khám). */
    public static final List<String> ACTIVE = List.of("WAITING", "EXAMINING", "WAITING_TREATMENT", "IN_ROOM", "WAITING_CONCLUSION");
    private static final List<String> LIVE =
            List.of("WAITING", "EXAMINING", "WAITING_TREATMENT", "IN_ROOM", "WAITING_CONCLUSION", "COMPLETED");
    private static final String STALE_APPOINTMENT = "Phiếu khám vừa thay đổi trạng thái, vui lòng tải lại.";

    /** Kết quả một lần xếp chỗ: đã ngồi, hết chỗ, hay người đó không còn chờ (bị bên khác mời, hoàn tác hoặc huỷ trước). */
    private enum Seating { SEATED, FULL, GONE }

    private final CheckinRepository checkins;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final UserRepository users;
    private final Settings settings;
    private final JdbcTemplate jdbc;
    private final Tx tx;
    private final Clock clock;
    private final BusinessClock businessClock;

    public QueueService(CheckinRepository checkins, AppointmentRepository appointments, PatientRepository patients, UserRepository users,
            Settings settings, JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock, BusinessClock businessClock) {
        this.checkins = checkins;
        this.appointments = appointments;
        this.patients = patients;
        this.users = users;
        this.settings = settings;
        this.jdbc = jdbc;
        this.tx = new Tx(transactionManager);
        this.clock = clock;
        this.businessClock = businessClock;
    }

    public boolean getAutoInvite() {
        return Boolean.TRUE.equals(settings.get(AUTO_KEY, true, Boolean.class));
    }

    public void setAutoInvite(boolean value) {
        settings.set(AUTO_KEY, value);
    }

    // ---------- Tiện ích ----------

    // ---------- Nhóm ưu tiên ----------

    /**
     * Nhóm ưu tiên: phiếu đặt trước và đến đúng khung giờ (sớm tối đa 15 phút). Khách đến trực tiếp (đặt hộ ngay trong
     * ngày) hoặc đến sau khi khung giờ kết thúc xếp nhóm thường (BR.28).
     */
    public static String priorityGroupFor(LocalDate slotDate, String slotStart, String source, Instant createdAt, Instant checkInTime) {
        boolean walkIn = "COUNTER".equals(source) && VnTime.today(createdAt).equals(slotDate);
        if (walkIn) return "NORMAL";
        long start = VnTime.slotInstant(slotDate, slotStart).toEpochMilli();
        long windowStart = start - Rules.QUEUE_PRIORITY_EARLY_MINUTES * 60_000L;
        long windowEnd = start + Rules.SLOT_MINUTES * 60_000L;
        long t = checkInTime.toEpochMilli();
        return t >= windowStart && t < windowEnd ? "PRIORITY" : "NORMAL";
    }

    // ---------- Check-in ----------

    /** Đang trong giờ nhận bệnh (giờ Việt Nam): từ giờ mở cửa đến trước giờ đóng cửa. */
    public static boolean isReceivingTime(Instant now) {
        int m = VnTime.minutesOfDay(now);
        return m >= VnTime.hhmmToMinutes(Constants.RECEIVING_HOURS.open()) && m < VnTime.hhmmToMinutes(Constants.RECEIVING_HOURS.close());
    }

    public static void assertReceivingTime(Instant now) {
        if (!isReceivingTime(now)) {
            throw Errors.conflict("Ngoài giờ nhận bệnh (" + Constants.RECEIVING_HOURS.label() + ") nên không check-in được.", "CLOSED");
        }
    }

    /**
     * Đặt hộ kèm check-in ngay: kiểm tra trước khi tạo phiếu đúng các điều kiện ngày và giờ mà checkIn sẽ kiểm, để không tạo
     * phiếu rồi mới lỗi ở bước check-in (phiếu thừa vẫn giữ chỗ).
     */
    public void assertCanCheckInOn(LocalDate slotDate) {
        Instant now = businessClock.now();
        if (!slotDate.equals(VnTime.today(now))) throw wrongDay();
        assertReceivingTime(now);
    }

    private static RuntimeException wrongDay() {
        return Errors.conflict("Phiếu khám không thuộc ngày hôm nay. Đổi giờ khám sang hôm nay trước khi check-in.", "WRONG_DAY");
    }

    /**
     * Check-in một phiếu hôm nay (UC.7.3). Đổi trạng thái phiếu và tạo dòng check-in trong cùng một giao dịch: một trong hai
     * lỗi thì cả hai quay lui (thay cho code bù trừ của Node). Lấp chỗ (nếu tự động mời đang bật) chạy sau khi giao dịch
     * đã commit. Trả về mã check-in mới.
     */
    public long checkIn(User staff, long appointmentId) {
        Instant now = businessClock.now().truncatedTo(ChronoUnit.MILLIS);
        Appointment appt = appointments.findById(appointmentId).orElseThrow(() -> Errors.notFound("Không tìm thấy phiếu khám."));
        if ("CANCELLED".equals(appt.getStatus())) throw Errors.conflict("Phiếu khám đã huỷ nên không check-in được.", "CANCELLED");
        if (!"BOOKED".equals(appt.getStatus())) throw Errors.conflict("Phiếu khám này đã được check-in.", "ALREADY_CHECKED_IN");
        LocalDate today = VnTime.today(now);
        if (!appt.getSlotDate().equals(today)) throw wrongDay();
        assertReceivingTime(now);
        Patient patient = patients.findById(appt.getPatientId()).orElse(null);
        if (patient != null && patient.isLocked()) throw Errors.forbidden("Hồ sơ đang bị khoá nên không ghi nhận lượt khám mới.");

        String group = priorityGroupFor(appt.getSlotDate(), appt.getSlotStart(), appt.getSource(), appt.getCreatedAt(), now);
        long checkinId = tx.run(() -> {
            int changed = jdbc.update("UPDATE appointments SET status = 'CHECKED_IN', updated_at = ? WHERE id = ? AND status = 'BOOKED'",
                    Tx.utc(clock.instant()), appt.getId());
            if (changed != 1) throw Errors.conflict(STALE_APPOINTMENT, "STALE");
            var keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("INSERT INTO checkins (appointment_id, patient_id, visit_date, check_in_time, "
                        + "priority_group, status, created_at) VALUES (?, ?, ?, ?, ?, 'WAITING', ?)", Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, appt.getId());
                ps.setLong(2, appt.getPatientId());
                ps.setObject(3, today);
                ps.setObject(4, Tx.utc(now));
                ps.setString(5, group);
                ps.setObject(6, Tx.utc(clock.instant()));
                return ps;
            }, keys);
            return keys.getKey().longValue();
        });
        // Người vừa check-in vào hàng chờ khám của bác sĩ; chỗ điều trị chỉ dành cho người đã được chỉ định
        return checkinId;
    }

    /** Hoàn tác check-in khi nhầm: phiếu về Chưa đến, check-in thành REVERTED (BR.27). */
    public void undoCheckIn(long appointmentId) {
        Checkin checkin = checkins.findFirstByAppointmentIdAndStatusIn(appointmentId, ACTIVE)
                .orElseThrow(() -> Errors.conflict("Phiếu khám này chưa được check-in.", "NOT_CHECKED_IN"));
        if (!"WAITING".equals(checkin.getStatus())) {
            throw Errors.conflict("Bệnh nhân đã được bác sĩ gọi vào khám nên không hoàn tác check-in được.", "IN_ROOM");
        }
        tx.run(() -> {
            jdbc.update("UPDATE appointments SET status = 'BOOKED', updated_at = ? WHERE id = ? AND status = 'CHECKED_IN'",
                    Tx.utc(clock.instant()), appointmentId);
            int reverted = jdbc.update("UPDATE checkins SET status = 'REVERTED' WHERE id = ? AND status = 'WAITING'", checkin.getId());
            if (reverted != 1) throw Errors.conflict("Hàng đợi vừa thay đổi, vui lòng tải lại.", "STALE");
            return null;
        });
    }

    /**
     * Người đã check-in mà huỷ phiếu thì rời hàng đợi và nhả chỗ. Chạy trong giao dịch của việc huỷ phiếu (không tự mở giao
     * dịch), nên huỷ phiếu và rời hàng cùng thành công hoặc cùng quay lui. Node không mời người kế tiếp lúc này.
     */
    public int leaveQueue(long appointmentId) {
        return jdbc.update("UPDATE checkins SET status = 'REVERTED', seat_no = NULL WHERE appointment_id = ? AND status IN "
                + "('WAITING', 'EXAMINING', 'WAITING_TREATMENT', 'IN_ROOM', 'WAITING_CONCLUSION')", appointmentId);
    }

    // ---------- Hàng đợi ----------

    private static void sortWaiting(List<Checkin> list) {
        list.sort(Comparator.comparing((Checkin c) -> "PRIORITY".equals(c.getPriorityGroup()) ? 0 : 1)
                .thenComparing(Checkin::getCheckInTime).thenComparing(Checkin::getId));
    }

    /** Hàng chờ khám: ưu tiên trước, rồi theo giờ check-in. */
    private List<Checkin> waitingList(LocalDate date) {
        List<Checkin> list = new ArrayList<>(checkins.findByVisitDateAndStatus(date, "WAITING"));
        sortWaiting(list);
        return list;
    }

    /** Hàng chờ điều trị: ai được chỉ định trước vào chỗ trước. */
    private static void sortTreatment(List<Checkin> list) {
        list.sort(Comparator.comparing(Checkin::getOrderedAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Checkin::getId));
    }

    private List<Checkin> treatmentList(LocalDate date) {
        List<Checkin> list = new ArrayList<>(checkins.findByVisitDateAndStatus(date, "WAITING_TREATMENT"));
        sortTreatment(list);
        return list;
    }

    // ---------- Bác sĩ: gọi khám, khám sơ bộ, chỉ định ----------

    private static RuntimeException notYours(String doctorName) {
        return Errors.forbidden("Bệnh nhân này do " + (doctorName == null ? "bác sĩ khác" : "bác sĩ " + doctorName) + " khám.");
    }

    /** Bác sĩ chỉ thao tác trên bệnh nhân mình đã gọi khám; Quản trị viên thao tác được mọi lượt. */
    public void assertOwnPatient(User staff, Checkin checkin) {
        if ("ADMIN".equals(staff.getRole())) return;
        if (checkin.getDoctorId() != null && !checkin.getDoctorId().equals(staff.getId())) {
            throw notYours(users.findById(checkin.getDoctorId()).map(User::getFullName).orElse(null));
        }
    }

    /** Gọi một người từ hàng chờ khám vào khám sơ bộ. false: người đó không còn chờ (bị gọi, hoàn tác hoặc huỷ trước). */
    private boolean callOne(Checkin checkin, User doctor, Instant now) {
        Boolean called = tx.once(status -> {
            int moved = jdbc.update("UPDATE appointments SET status = 'IN_PROGRESS', updated_at = ? WHERE id = ? AND status = 'CHECKED_IN'",
                    Tx.utc(clock.instant()), checkin.getAppointmentId());
            if (moved != 1) {
                status.setRollbackOnly();
                return false;
            }
            int changed = jdbc.update("UPDATE checkins SET status = 'EXAMINING', doctor_id = ?, exam_started_at = ? WHERE id = ? AND status = 'WAITING'",
                    doctor.getId(), Tx.utc(now), checkin.getId());
            if (changed != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(called);
    }

    /** Bác sĩ gọi người đầu hàng chờ khám. Trả về mã check-in vừa được gọi. */
    public long callNext(User doctor, LocalDate date, Instant now) {
        Set<Long> skipped = new HashSet<>();
        for (int attempt = 0; attempt < 50; attempt++) {
            List<Checkin> waiting = waitingList(date).stream().filter(c -> !skipped.contains(c.getId())).toList();
            if (waiting.isEmpty()) throw Errors.conflict("Chưa có ai đang chờ khám.", "QUEUE_EMPTY");
            Checkin head = waiting.get(0);
            if (callOne(head, doctor, now)) return head.getId();
            skipped.add(head.getId());
        }
        throw Errors.conflict("Hàng chờ vừa thay đổi, vui lòng thử lại.", "STALE");
    }

    /** Bác sĩ gọi đúng một người trong hàng chờ khám (gọi trước người đứng trên). */
    public void call(User doctor, long checkinId, Instant now) {
        Checkin checkin = checkins.findById(checkinId).filter(c -> "WAITING".equals(c.getStatus())).orElseThrow(QueueService::notWaiting);
        if (!callOne(checkin, doctor, now)) throw notWaiting();
    }

    private Checkin requireStatus(long checkinId, String status, String message, String code) {
        return checkins.findById(checkinId).filter(c -> status.equals(c.getStatus())).orElseThrow(() -> Errors.conflict(message, code));
    }

    private static final String NOT_EXAMINING = "Bệnh nhân này không còn đang khám sơ bộ.";
    private static final String NOT_WAITING_TREATMENT = "Bệnh nhân này không còn chờ điều trị.";
    private static final String NOT_ON_SEAT = "Bệnh nhân này không còn ở chỗ điều trị.";

    /** Gọi nhầm: người đó trở lại hàng chờ khám đúng vị trí theo giờ check-in. */
    public void undoCall(User staff, long checkinId) {
        Checkin checkin = requireStatus(checkinId, "EXAMINING", NOT_EXAMINING, "NOT_EXAMINING");
        assertOwnPatient(staff, checkin);
        tx.run(() -> {
            int moved = jdbc.update("UPDATE appointments SET status = 'CHECKED_IN', updated_at = ? WHERE id = ? AND status = 'IN_PROGRESS'",
                    Tx.utc(clock.instant()), checkin.getAppointmentId());
            if (moved != 1) throw Errors.conflict(STALE_APPOINTMENT, "STALE");
            int changed = jdbc.update("UPDATE checkins SET status = 'WAITING', doctor_id = NULL, exam_started_at = NULL WHERE id = ? "
                    + "AND status = 'EXAMINING'", checkinId);
            if (changed != 1) throw Errors.conflict(NOT_EXAMINING, "NOT_EXAMINING");
            return null;
        });
    }

    /**
     * Bác sĩ lưu khám sơ bộ và chỉ định điều trị: người đó sang hàng chờ điều trị. Nếu tự động mời đang bật, chỗ điều trị
     * trống nhận người đó ngay sau khi lưu.
     */
    public void orderTreatment(User staff, long checkinId, OrderData data) {
        Checkin checkin = requireStatus(checkinId, "EXAMINING", NOT_EXAMINING, "NOT_EXAMINING");
        assertOwnPatient(staff, checkin);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        int changed = jdbc.update("UPDATE checkins SET status = 'WAITING_TREATMENT', symptoms = ?, prelim_diagnosis = ?, prelim_pulse = ?, "
                + "prelim_blood_pressure = ?, medical_history = ?, treatment_kinds = ?, treatment_order = ?, treatment_other = ?, ordered_at = ? WHERE id = ? AND status = 'EXAMINING'",
                data.symptoms(), data.prelimDiagnosis(), data.pulse(), data.bloodPressure(), data.medicalHistory(), data.kindsCsv(), data.treatmentOrder(), data.treatmentOther(), Tx.utc(now),
                checkinId);
        if (changed != 1) throw Errors.conflict(NOT_EXAMINING, "NOT_EXAMINING");
        if (getAutoInvite()) fillSeatsAfterCommit(checkin.getVisitDate(), List.of());
    }

    /** Sửa chỉ định khi còn chờ chỗ: người đó trở lại khám sơ bộ với bác sĩ đã khám, nội dung đã ghi vẫn giữ. */
    public void undoOrder(User staff, long checkinId) {
        Checkin checkin = requireStatus(checkinId, "WAITING_TREATMENT", NOT_WAITING_TREATMENT, "NOT_WAITING_TREATMENT");
        assertOwnPatient(staff, checkin);
        int changed = jdbc.update("UPDATE checkins SET status = 'EXAMINING', ordered_at = NULL WHERE id = ? AND status = 'WAITING_TREATMENT'",
                checkinId);
        if (changed != 1) throw Errors.conflict(NOT_WAITING_TREATMENT, "NOT_WAITING_TREATMENT");
    }

    // ---------- Kỹ thuật viên: báo cáo điều trị ----------

    /**
     * Kỹ thuật viên lưu báo cáo: chỗ trả lại ngay, người đó chờ bác sĩ kết luận. Chỗ trống nhận người kế tiếp nếu tự động mời
     * đang bật. Người lưu báo cáo được ghi là kỹ thuật viên đã điều trị.
     */
    public void finishTreatment(User technician, long checkinId, ReportData data) {
        Checkin checkin = requireStatus(checkinId, "IN_ROOM", NOT_ON_SEAT, "NOT_IN_ROOM");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        int changed = jdbc.update("UPDATE checkins SET status = 'WAITING_CONCLUSION', seat_no = NULL, technician_id = ?, "
                + "report_acupuncture = ?, report_acupressure = ?, report_physio = ?, report_other = ?, report_note = ?, treated_at = ? WHERE id = ? "
                + "AND status = 'IN_ROOM'",
                technician.getId(), data.acupuncture(), data.acupressure(), data.physio(), data.other(), data.note(), Tx.utc(now), checkinId);
        if (changed != 1) throw Errors.conflict(NOT_ON_SEAT, "NOT_IN_ROOM");
        fillAfterCompletion(checkin.getVisitDate());
    }

    private Integer lowestFreeSeat(LocalDate date) {
        Set<Integer> taken = new HashSet<>(
                jdbc.queryForList("SELECT seat_no FROM checkins WHERE visit_date = ? AND status = 'IN_ROOM'", Integer.class, date));
        for (int seat = 1; seat <= SEATS; seat++) if (!taken.contains(seat)) return seat;
        return null;
    }

    /**
     * Mời một người vào chỗ trống thấp nhất. Mỗi lần thử chỗ là một giao dịch riêng: chỗ vừa bị người khác nhận (khoá
     * duy nhất visit_date + chỗ) thì lần thử đó quay lui và thử chỗ kế tiếp, tối đa 5 lần như seatOne của Node.
     */
    private Seating seatOne(Checkin checkin, LocalDate date, Instant now, Long technicianId) {
        int duplicates = 0;
        int deadlocks = 0;
        while (duplicates < SEATS) {
            Integer seat = lowestFreeSeat(date);
            if (seat == null) return Seating.FULL;
            try {
                Boolean seated = tx.once(status -> {
                    // Khoá phiếu khám trước (cùng thứ tự với huỷ phiếu). Phiếu không còn đang khám (đã huỷ...): không xếp chỗ
                    int appointmentMoved = jdbc.update("UPDATE appointments SET updated_at = ? WHERE id = ? AND status = 'IN_PROGRESS'",
                            Tx.utc(clock.instant()), checkin.getAppointmentId());
                    if (appointmentMoved != 1) {
                        status.setRollbackOnly();
                        return false;
                    }
                    int changed = jdbc.update("UPDATE checkins SET status = 'IN_ROOM', seat_no = ?, entered_at = ?, technician_id = ? "
                            + "WHERE id = ? AND status = 'WAITING_TREATMENT'", seat, Tx.utc(now), technicianId, checkin.getId());
                    if (changed != 1) {
                        status.setRollbackOnly();
                        return false;
                    }
                    return true;
                });
                return Boolean.TRUE.equals(seated) ? Seating.SEATED : Seating.GONE;
            } catch (DuplicateKeyException e) {
                duplicates++;
            } catch (RuntimeException e) {
                if (!Tx.isDeadlock(e) || ++deadlocks >= Tx.MAX_ATTEMPTS) throw e;
            }
        }
        return Seating.FULL;
    }

    /**
     * Mời người đầu hàng chờ điều trị vào chỗ. Trả về mã check-in vừa vào chỗ. Người đầu hàng không xếp chỗ được mà vẫn còn
     * chờ (phiếu của họ không còn đang khám) bị bỏ qua trong lần gọi này và thử người kế tiếp, nên một dòng hỏng không chặn
     * cả hàng và cũng không báo đầy chỗ oan. {@code technician}: người mời (null khi tự động mời).
     */
    public long inviteNext(LocalDate date, Instant now, User technician) {
        Set<Long> skipped = new HashSet<>();
        Long technicianId = technician == null ? null : technician.getId();
        // Người đầu hàng bị lấy mất giữa chừng (mời đồng thời) thì chọn lại người đầu mới
        for (int attempt = 0; attempt < 50; attempt++) {
            List<Checkin> waiting = treatmentList(date).stream().filter(c -> !skipped.contains(c.getId())).toList();
            if (waiting.isEmpty()) throw Errors.conflict("Chưa có ai đang chờ điều trị.", "QUEUE_EMPTY");
            Checkin head = waiting.get(0);
            switch (seatOne(head, date, now, technicianId)) {
                case SEATED -> {
                    return head.getId();
                }
                case FULL -> throw roomFull();
                case GONE -> skipped.add(head.getId());
            }
        }
        throw roomFull();
    }

    private static RuntimeException roomFull() {
        return Errors.conflict("Đã đủ " + SEATS + " chỗ điều trị.", "ROOM_FULL");
    }

    /** Mời đúng một người trong hàng chờ điều trị vào chỗ (mời trước người đứng trên). */
    public void seat(long checkinId, Instant now, User technician) {
        Checkin checkin = checkins.findById(checkinId).filter(c -> "WAITING_TREATMENT".equals(c.getStatus()))
                .orElseThrow(QueueService::notWaiting);
        switch (seatOne(checkin, checkin.getVisitDate(), now, technician == null ? null : technician.getId())) {
            case SEATED -> { }
            case FULL -> throw roomFull();
            case GONE -> throw notWaiting();
        }
    }

    private static RuntimeException notWaiting() {
        return Errors.conflict("Người này không còn trong hàng chờ.", "NOT_WAITING");
    }

    private static RuntimeException notInRoom() {
        return Errors.conflict(NOT_ON_SEAT, "NOT_IN_ROOM");
    }

    /** Tự động lấp chỗ điều trị trống bằng người đầu hàng chờ điều trị (BR.29). {@code exclude}: các check-in không được mời lần này. */
    public void fillSeats(LocalDate date, Collection<Long> exclude, Instant now) {
        for (Checkin c : treatmentList(date)) {
            if (exclude.contains(c.getId())) continue;
            if (seatOne(c, date, now, null) == Seating.FULL) break;
        }
    }

    public void fillSeats(LocalDate date) {
        fillSeats(date, List.of(), clock.instant());
    }

    /**
     * Lấp chỗ sau khi thay đổi chính đã commit. Lỗi ở đây không được làm yêu cầu báo thất bại (việc chính đã xong, như
     * RecordService sau khi lưu bệnh án); lần check-in hoặc mời kế tiếp sẽ lấp lại.
     */
    public void fillSeatsAfterCommit(LocalDate date, Collection<Long> exclude) {
        try {
            fillSeats(date, exclude, clock.instant());
        } catch (RuntimeException e) {
            log.warn("Không lấp được chỗ trống ngày {} sau khi thay đổi hàng đợi đã lưu", date, e);
        }
    }

    /** Hoàn tác vào chỗ: trở lại hàng chờ điều trị đúng vị trí theo giờ chỉ định (BR.27). */
    public void undoEnter(long checkinId) {
        Checkin checkin = checkins.findById(checkinId).filter(c -> "IN_ROOM".equals(c.getStatus())).orElseThrow(QueueService::notInRoom);
        tx.run(() -> {
            // Khoá phiếu khám trước; phiếu không còn đang khám (đã hoàn thành, huỷ...): quay lui, không đưa người đã xong về hàng chờ
            int appointmentLocked = jdbc.update("UPDATE appointments SET updated_at = ? WHERE id = ? AND status = 'IN_PROGRESS'",
                    Tx.utc(clock.instant()), checkin.getAppointmentId());
            if (appointmentLocked != 1) throw Errors.conflict(STALE_APPOINTMENT, "STALE");
            int changed = jdbc.update("UPDATE checkins SET status = 'WAITING_TREATMENT', seat_no = NULL, entered_at = NULL, technician_id = NULL "
                    + "WHERE id = ? AND status = 'IN_ROOM'", checkinId);
            if (changed != 1) throw notInRoom();
            return null;
        });
        // Chỗ vừa trống nhận người kế tiếp; người vừa hoàn tác vào ở chỗ trống sau
        if (getAutoInvite()) fillSeatsAfterCommit(checkin.getVisitDate(), List.of(checkinId));
    }

    /**
     * Lượt khám xong khi bác sĩ lưu phiếu kết quả (BR.30): check-in đang khám sơ bộ (không cần điều trị) hoặc chờ kết luận thành
     * COMPLETED. Chỉ là một câu UPDATE, chạy trong giao dịch của nơi gọi (lưu bệnh án, đổi phiếu khám sang COMPLETED và câu này
     * cùng thành công hoặc cùng quay lui). Trả về false nếu check-in không còn ở hai trạng thái đó.
     */
    public boolean markCheckinCompleted(long checkinId, Instant now) {
        return jdbc.update("UPDATE checkins SET status = 'COMPLETED', completed_at = ?, seat_no = NULL WHERE id = ? "
                + "AND status IN ('EXAMINING', 'WAITING_CONCLUSION')", Tx.utc(now), checkinId) == 1;
    }

    /** Mời người kế tiếp vào chỗ vừa trống nếu tự động mời đang bật. Phải gọi ngoài giao dịch, sau khi giao dịch hoàn thành đã commit. */
    public void fillAfterCompletion(LocalDate visitDate) {
        if (getAutoInvite()) fillSeatsAfterCommit(visitDate, List.of());
    }

    // ---------- Xem hàng đợi ----------

    private Map<Long, Patient> patientsById(Collection<Long> ids) {
        Map<Long, Patient> out = new HashMap<>();
        for (Patient p : patients.findAllById(ids)) out.put(p.getId(), p);
        return out;
    }

    private static List<String> kinds(String csv) {
        return csv == null || csv.isEmpty() ? List.of() : List.of(csv.split(","));
    }

    /** Khám sơ bộ, chỉ định và báo cáo điều trị của một lượt (null khi chưa có gì). */
    public static Map<String, Object> visitDetail(Checkin c, Map<Long, String> names) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doctorId", c.getDoctorId() == null ? null : String.valueOf(c.getDoctorId()));
        out.put("doctorName", c.getDoctorId() == null ? null : names.get(c.getDoctorId()));
        out.put("examStartedAt", c.getExamStartedAt());
        out.put("symptoms", c.getSymptoms());
        out.put("prelimDiagnosis", c.getPrelimDiagnosis());
        out.put("pulse", c.getPrelimPulse());
        out.put("bloodPressure", c.getPrelimBloodPressure());
        out.put("medicalHistory", c.getMedicalHistory());
        out.put("treatmentKinds", kinds(c.getTreatmentKinds()));
        out.put("treatmentOrder", c.getTreatmentOrder());
        out.put("treatmentOther", c.getTreatmentOther());
        out.put("orderedAt", c.getOrderedAt());
        out.put("technicianId", c.getTechnicianId() == null ? null : String.valueOf(c.getTechnicianId()));
        out.put("technicianName", c.getTechnicianId() == null ? null : names.get(c.getTechnicianId()));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("acupuncture", c.getReportAcupuncture());
        report.put("acupressure", c.getReportAcupressure());
        report.put("physio", c.getReportPhysio());
        report.put("other", c.getReportOther());
        report.put("note", c.getReportNote());
        out.put("report", c.getTreatedAt() == null ? null : report);
        out.put("treatedAt", c.getTreatedAt());
        return out;
    }

    /** Tên nhân viên (bác sĩ, kỹ thuật viên) của các lượt, gom một lần. */
    public Map<Long, String> staffNames(Collection<Checkin> list) {
        Set<Long> ids = new HashSet<>();
        for (Checkin c : list) {
            if (c.getDoctorId() != null) ids.add(c.getDoctorId());
            if (c.getTechnicianId() != null) ids.add(c.getTechnicianId());
        }
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (User u : users.findAllById(ids)) out.put(u.getId(), u.getFullName());
        return out;
    }

    /** Lượt check-in đang hiệu lực (hoặc đã hoàn thành) của một phiếu khám, kèm khám sơ bộ và báo cáo; null nếu không có. */
    public Map<String, Object> visitByAppointment(long appointmentId) {
        Checkin c = checkins.findFirstByAppointmentIdAndStatusIn(appointmentId, LIVE).orElse(null);
        if (c == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(c.getId()));
        out.put("status", c.getStatus());
        out.put("seatNo", c.getSeatNo());
        out.putAll(visitDetail(c, staffNames(List.of(c))));
        return out;
    }

    private static Map<String, Object> checkinView(Checkin c, Appointment appt, Patient patient, Instant now, Map<Long, String> names) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(c.getId()));
        out.put("appointmentId", String.valueOf(c.getAppointmentId()));
        out.put("ticketCode", appt == null ? null : appt.getTicketCode());
        out.put("slotStart", appt == null ? null : appt.getSlotStart());
        out.put("serviceType", appt == null ? null : appt.getServiceType());
        out.put("source", appt == null ? null : appt.getSource());
        if (patient == null) {
            out.put("patient", null);
        } else {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", String.valueOf(patient.getId()));
            p.put("fullName", patient.getFullName());
            p.put("patientCode", patient.getPatientCode());
            p.put("birthYear", patient.getBirthYear());
            out.put("patient", p);
        }
        out.put("priorityGroup", c.getPriorityGroup());
        out.put("status", c.getStatus());
        out.put("seatNo", c.getSeatNo());
        out.put("checkInTime", c.getCheckInTime());
        out.put("enteredAt", c.getEnteredAt());
        out.put("waitedMinutes", Math.max(0L, (long) Math.floor((now.toEpochMilli() - c.getCheckInTime().toEpochMilli()) / 60_000.0 + 0.5)));
        out.putAll(visitDetail(c, names));
        return out;
    }

    public Map<String, Object> queueState(LocalDate date, Instant now) {
        List<Checkin> all = checkins.findByVisitDateAndStatusIn(date, LIVE);
        Map<Long, Appointment> apptMap = new HashMap<>();
        for (Appointment a : appointments.findAllById(all.stream().map(Checkin::getAppointmentId).toList())) apptMap.put(a.getId(), a);
        Map<Long, Patient> pMap = patientsById(all.stream().map(Checkin::getPatientId).toList());
        Map<Long, String> names = staffNames(all);
        java.util.function.Function<Checkin, Map<String, Object>> view =
                c -> checkinView(c, apptMap.get(c.getAppointmentId()), pMap.get(c.getPatientId()), now, names);

        List<Checkin> inRoom = new ArrayList<>(all.stream().filter(c -> "IN_ROOM".equals(c.getStatus())).toList());
        inRoom.sort(Comparator.comparing(Checkin::getSeatNo));
        List<Checkin> waiting = new ArrayList<>(all.stream().filter(c -> "WAITING".equals(c.getStatus())).toList());
        sortWaiting(waiting);
        List<Checkin> examining = new ArrayList<>(all.stream().filter(c -> "EXAMINING".equals(c.getStatus())).toList());
        examining.sort(Comparator.comparing(Checkin::getExamStartedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        List<Checkin> treatmentWaiting = new ArrayList<>(all.stream().filter(c -> "WAITING_TREATMENT".equals(c.getStatus())).toList());
        sortTreatment(treatmentWaiting);
        List<Checkin> conclusion = new ArrayList<>(all.stream().filter(c -> "WAITING_CONCLUSION".equals(c.getStatus())).toList());
        conclusion.sort(Comparator.comparing(Checkin::getTreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        List<Map<String, Object>> seatList = new ArrayList<>();
        for (int seat = 1; seat <= SEATS; seat++) {
            final int n = seat;
            Checkin c = inRoom.stream().filter(x -> x.getSeatNo() == n).findFirst().orElse(null);
            if (c != null) {
                seatList.add(view.apply(c));
            } else {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("seatNo", n);
                empty.put("empty", true);
                seatList.add(empty);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("capacity", SEATS);
        out.put("autoInvite", getAutoInvite());
        out.put("seats", seatList);
        out.put("inRoomCount", inRoom.size());
        out.put("waiting", waiting.stream().map(view).toList());
        out.put("waitingCount", waiting.size());
        out.put("examining", examining.stream().map(view).toList());
        out.put("treatmentWaiting", treatmentWaiting.stream().map(view).toList());
        out.put("treatmentWaitingCount", treatmentWaiting.size());
        out.put("awaitingConclusion", conclusion.stream().map(view).toList());
        out.put("completedCount", all.stream().filter(c -> "COMPLETED".equals(c.getStatus())).count());
        out.put("slotMap", slotMap(date, all, apptMap, pMap));
        return out;
    }

    /** Sơ đồ chỗ theo khung giờ: mỗi ô là một người đã check-in vào khung đó (UC.10.5). */
    private List<Map<String, Object>> slotMap(LocalDate date, List<Checkin> all, Map<Long, Appointment> apptMap, Map<Long, Patient> pMap) {
        Map<String, int[]> booked = new HashMap<>();
        jdbc.query("SELECT slot_start, COUNT(*), SUM(status = 'BOOKED') FROM appointments WHERE slot_date = ? AND status <> 'CANCELLED' "
                + "GROUP BY slot_start", rs -> {
                    booked.put(rs.getString(1), new int[] {rs.getInt(2), rs.getInt(3)});
                }, date);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String start : Rules.SLOTS) {
            List<Checkin> inSlot = new ArrayList<>(all.stream()
                    .filter(c -> apptMap.get(c.getAppointmentId()) != null && start.equals(apptMap.get(c.getAppointmentId()).getSlotStart())).toList());
            inSlot.sort(Comparator.comparing(Checkin::getCheckInTime).thenComparing(Checkin::getId));
            List<Map<String, Object>> people = new ArrayList<>();
            for (Checkin c : inSlot) {
                Map<String, Object> person = new LinkedHashMap<>();
                person.put("id", String.valueOf(c.getId()));
                Patient p = pMap.get(c.getPatientId());
                person.put("fullName", p == null ? null : p.getFullName());
                person.put("ticketCode", apptMap.get(c.getAppointmentId()).getTicketCode());
                person.put("status", c.getStatus());
                person.put("seatNo", c.getSeatNo());
                people.add(person);
            }
            int[] b = booked.getOrDefault(start, new int[] {0, 0});
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("start", start);
            slot.put("startMinutes", VnTime.hhmmToMinutes(start));
            slot.put("booked", b[0]);
            slot.put("capacity", Rules.SLOT_CAPACITY);
            slot.put("notArrived", b[1]);
            slot.put("checkins", people);
            out.add(slot);
        }
        return out;
    }

    /**
     * Thông tin check-in của từng phiếu trong ngày, gắn vào Lịch trong ngày: giờ check-in, giờ vào khám, nhóm ưu tiên và
     * thứ tự trong hàng chờ. Khoá của kết quả là mã phiếu khám.
     */
    public Map<Long, Map<String, Object>> checkinsByAppointment(Collection<Long> appointmentIds) {
        Map<Long, Map<String, Object>> out = new HashMap<>();
        if (appointmentIds.isEmpty()) return out;
        List<Checkin> found = checkins.findByAppointmentIdInAndStatusIn(appointmentIds, LIVE);
        // Thứ tự tính trên cả hàng chờ của ngày, không chỉ các phiếu đang hiện: danh sách có thể đã lọc theo trạng thái hay từ khoá
        Set<LocalDate> waitingDates = new HashSet<>();
        for (Checkin c : found) if ("WAITING".equals(c.getStatus())) waitingDates.add(c.getVisitDate());
        Map<Long, Integer> order = new HashMap<>();
        for (LocalDate date : waitingDates) {
            List<Checkin> list = new ArrayList<>(checkins.findByVisitDateAndStatus(date, "WAITING"));
            sortWaiting(list);
            for (int i = 0; i < list.size(); i++) order.put(list.get(i).getId(), i + 1);
        }
        for (Checkin c : found) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", String.valueOf(c.getId()));
            view.put("status", c.getStatus());
            view.put("priorityGroup", c.getPriorityGroup());
            view.put("checkInTime", c.getCheckInTime());
            view.put("enteredAt", c.getEnteredAt());
            view.put("queueNo", order.get(c.getId()));
            out.put(c.getAppointmentId(), view);
        }
        return out;
    }
}
