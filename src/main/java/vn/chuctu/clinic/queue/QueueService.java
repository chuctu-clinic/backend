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

/**
 * Check-in, hàng đợi và mời vào khám (services/queue.service.js).
 *
 * <p>Đồng hồ: {@link #checkIn}, {@link #isReceivingTime} và {@link #assertReceivingTime} dùng {@link BusinessClock} (chỗ Node
 * dùng clock.now); {@link #inviteNext}, {@link #enterRoom}, {@link #fillSeats}, {@link #markCheckinCompleted} và các giờ ghi
 * vào database dùng {@link Clock} (chỗ Node dùng new Date()).
 *
 * <p>Một ghế không bao giờ có hai người nhờ ràng buộc UNIQUE (visit_date, in_room_seat_no) của cột sinh tự động. Mỗi lần thử
 * một ghế ({@link #seatOne}) chạy trong giao dịch riêng, để lỗi trùng khoá chỉ quay lui lần thử đó chứ không làm hỏng
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
    private static final List<String> LIVE = List.of("WAITING", "IN_ROOM", "COMPLETED");
    private static final String STALE_APPOINTMENT = "Phiếu khám vừa thay đổi trạng thái, vui lòng tải lại.";

    /** Kết quả một lần xếp ghế: đã ngồi, hết ghế, hay người đó không còn chờ (bị bên khác mời, hoàn tác hoặc huỷ trước). */
    private enum Seating { SEATED, FULL, GONE }

    private final CheckinRepository checkins;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final Settings settings;
    private final JdbcTemplate jdbc;
    private final Tx tx;
    private final Clock clock;
    private final BusinessClock businessClock;

    public QueueService(CheckinRepository checkins, AppointmentRepository appointments, PatientRepository patients, Settings settings,
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock, BusinessClock businessClock) {
        this.checkins = checkins;
        this.appointments = appointments;
        this.patients = patients;
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

    /** Kiểm tra giờ nhận bệnh theo đồng hồ nghiệp vụ, cho đặt hộ kèm check-in ngay. */
    public void assertReceivingTime() {
        assertReceivingTime(businessClock.now());
    }

    /**
     * Check-in một phiếu hôm nay (UC.7.3). Đổi trạng thái phiếu và tạo dòng check-in trong cùng một giao dịch: một trong hai
     * lỗi thì cả hai quay lui (thay cho code bù trừ của Node). Lấp ghế (nếu tự động mời đang bật) chạy sau khi giao dịch
     * đã commit. Trả về mã check-in mới.
     */
    public long checkIn(User staff, long appointmentId) {
        Instant now = businessClock.now().truncatedTo(ChronoUnit.MILLIS);
        Appointment appt = appointments.findById(appointmentId).orElseThrow(() -> Errors.notFound("Không tìm thấy phiếu khám."));
        if ("CANCELLED".equals(appt.getStatus())) throw Errors.conflict("Phiếu khám đã huỷ nên không check-in được.", "CANCELLED");
        if (!"BOOKED".equals(appt.getStatus())) throw Errors.conflict("Phiếu khám này đã được check-in.", "ALREADY_CHECKED_IN");
        LocalDate today = VnTime.today(now);
        if (!appt.getSlotDate().equals(today)) {
            throw Errors.conflict("Phiếu khám không thuộc ngày hôm nay. Đổi giờ khám sang hôm nay trước khi check-in.", "WRONG_DAY");
        }
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
        if (getAutoInvite()) fillSeatsAfterCommit(today, List.of());
        return checkinId;
    }

    /** Hoàn tác check-in khi nhầm: phiếu về Chưa đến, check-in thành REVERTED (BR.27). */
    public void undoCheckIn(long appointmentId) {
        Checkin checkin = checkins.findFirstByAppointmentIdAndStatusIn(appointmentId, List.of("WAITING", "IN_ROOM"))
                .orElseThrow(() -> Errors.conflict("Phiếu khám này chưa được check-in.", "NOT_CHECKED_IN"));
        if ("IN_ROOM".equals(checkin.getStatus())) {
            throw Errors.conflict("Bệnh nhân đã vào khám. Hoàn tác vào khám trước.", "IN_ROOM");
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
     * Người đã check-in mà huỷ phiếu thì rời hàng đợi và nhả ghế. Chạy trong giao dịch của việc huỷ phiếu (không tự mở giao
     * dịch), nên huỷ phiếu và rời hàng cùng thành công hoặc cùng quay lui. Node không mời người kế tiếp lúc này.
     */
    public int leaveQueue(long appointmentId) {
        return jdbc.update("UPDATE checkins SET status = 'REVERTED', seat_no = NULL WHERE appointment_id = ? AND status IN ('WAITING', 'IN_ROOM')",
                appointmentId);
    }

    // ---------- Hàng đợi ----------

    private static void sortWaiting(List<Checkin> list) {
        list.sort(Comparator.comparing((Checkin c) -> "PRIORITY".equals(c.getPriorityGroup()) ? 0 : 1)
                .thenComparing(Checkin::getCheckInTime).thenComparing(Checkin::getId));
    }

    private List<Checkin> waitingList(LocalDate date) {
        List<Checkin> list = new ArrayList<>(checkins.findByVisitDateAndStatus(date, "WAITING"));
        sortWaiting(list);
        return list;
    }

    private Integer lowestFreeSeat(LocalDate date) {
        Set<Integer> taken = new HashSet<>(
                jdbc.queryForList("SELECT seat_no FROM checkins WHERE visit_date = ? AND status = 'IN_ROOM'", Integer.class, date));
        for (int seat = 1; seat <= SEATS; seat++) if (!taken.contains(seat)) return seat;
        return null;
    }

    /**
     * Mời một người vào ghế trống thấp nhất. Mỗi lần thử ghế là một giao dịch riêng: ghế vừa bị người khác nhận (khoá
     * duy nhất visit_date + ghế) thì lần thử đó quay lui và thử ghế kế tiếp, tối đa 5 lần như seatOne của Node.
     */
    private Seating seatOne(Checkin checkin, LocalDate date, Instant now) {
        int duplicates = 0;
        int deadlocks = 0;
        while (duplicates < SEATS) {
            Integer seat = lowestFreeSeat(date);
            if (seat == null) return Seating.FULL;
            try {
                Boolean seated = tx.once(status -> {
                    int appointmentMoved = jdbc.update(
                            "UPDATE appointments SET status = 'IN_PROGRESS', updated_at = ? WHERE id = ? AND status = 'CHECKED_IN'",
                            Tx.utc(clock.instant()), checkin.getAppointmentId());
                    // Phiếu không còn ở trạng thái đã check-in (đã hoàn thành, huỷ, hoàn tác...): không được xếp ghế cho phiếu đó
                    if (appointmentMoved != 1) {
                        status.setRollbackOnly();
                        return false;
                    }
                    int changed = jdbc.update("UPDATE checkins SET status = 'IN_ROOM', seat_no = ?, entered_at = ? WHERE id = ? AND status = 'WAITING'",
                            seat, Tx.utc(now), checkin.getId());
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
     * Mời người đầu hàng chờ vào khám (UC.10.2). Trả về mã check-in vừa vào ghế. Người đầu hàng không xếp ghế được mà vẫn còn
     * chờ (phiếu của họ không còn ở trạng thái đã check-in) bị bỏ qua trong lần gọi này và thử người kế tiếp, nên một dòng
     * hỏng không chặn cả hàng và cũng không báo đầy phòng oan.
     */
    public long inviteNext(LocalDate date, Instant now) {
        Set<Long> skipped = new HashSet<>();
        // Người đầu hàng bị lấy mất giữa chừng (mời đồng thời) thì chọn lại người đầu mới
        for (int attempt = 0; attempt < 50; attempt++) {
            List<Checkin> waiting = waitingList(date).stream().filter(c -> !skipped.contains(c.getId())).toList();
            if (waiting.isEmpty()) throw Errors.conflict("Chưa có ai đang chờ.", "QUEUE_EMPTY");
            Checkin head = waiting.get(0);
            switch (seatOne(head, date, now)) {
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
        return Errors.conflict("Phòng khám đã đủ " + SEATS + " người.", "ROOM_FULL");
    }

    /** Mời đúng một người đã check-in vào khám, bấm từ Lịch trong ngày. */
    public void enterRoom(long appointmentId, Instant now) {
        Checkin checkin = checkins.findFirstByAppointmentIdAndStatus(appointmentId, "WAITING").orElseThrow(QueueService::notWaiting);
        switch (seatOne(checkin, checkin.getVisitDate(), now)) {
            case SEATED -> { }
            case FULL -> throw roomFull();
            case GONE -> throw notWaiting();
        }
    }

    private static RuntimeException notWaiting() {
        return Errors.conflict("Người này không còn trong hàng chờ.", "NOT_WAITING");
    }

    /** Hoàn tác vào khám theo phiếu khám, bấm từ Lịch trong ngày. */
    public void undoEnterByAppointment(long appointmentId) {
        Checkin checkin = checkins.findFirstByAppointmentIdAndStatus(appointmentId, "IN_ROOM").orElseThrow(QueueService::notInRoom);
        undoEnter(checkin.getId());
    }

    private static RuntimeException notInRoom() {
        return Errors.conflict("Người này không còn trong phòng khám.", "NOT_IN_ROOM");
    }

    /** Tự động lấp ghế trống bằng người đầu hàng chờ (BR.29). {@code exclude}: các check-in không được mời lần này. */
    public void fillSeats(LocalDate date, Collection<Long> exclude, Instant now) {
        for (Checkin c : waitingList(date)) {
            if (exclude.contains(c.getId())) continue;
            if (seatOne(c, date, now) == Seating.FULL) break;
        }
    }

    public void fillSeats(LocalDate date) {
        fillSeats(date, List.of(), clock.instant());
    }

    /**
     * Lấp ghế sau khi thay đổi chính đã commit. Lỗi ở đây không được làm yêu cầu báo thất bại (việc chính đã xong, như
     * RecordService sau khi lưu bệnh án); lần check-in hoặc mời kế tiếp sẽ lấp lại.
     */
    public void fillSeatsAfterCommit(LocalDate date, Collection<Long> exclude) {
        try {
            fillSeats(date, exclude, clock.instant());
        } catch (RuntimeException e) {
            log.warn("Không lấp được ghế trống ngày {} sau khi thay đổi hàng đợi đã lưu", date, e);
        }
    }

    /** Hoàn tác vào khám: trở lại hàng chờ đúng vị trí theo giờ check-in (BR.27). */
    public void undoEnter(long checkinId) {
        Checkin checkin = checkins.findById(checkinId).filter(c -> "IN_ROOM".equals(c.getStatus())).orElseThrow(QueueService::notInRoom);
        tx.run(() -> {
            int appointmentMoved = jdbc.update("UPDATE appointments SET status = 'CHECKED_IN', updated_at = ? WHERE id = ? AND status = 'IN_PROGRESS'",
                    Tx.utc(clock.instant()), checkin.getAppointmentId());
            // Phiếu không còn đang khám (đã hoàn thành...): quay lui, không đưa người đã xong về hàng chờ
            if (appointmentMoved != 1) throw Errors.conflict(STALE_APPOINTMENT, "STALE");
            int changed = jdbc.update("UPDATE checkins SET status = 'WAITING', seat_no = NULL, entered_at = NULL WHERE id = ? AND status = 'IN_ROOM'",
                    checkinId);
            if (changed != 1) throw notInRoom();
            return null;
        });
        // Ghế vừa trống nhận người kế tiếp; người vừa hoàn tác vào ở ghế trống sau
        if (getAutoInvite()) fillSeatsAfterCommit(checkin.getVisitDate(), List.of(checkinId));
    }

    /**
     * Rời hàng đợi khi lưu phiếu kết quả (BR.30): check-in IN_ROOM thành COMPLETED và nhả ghế. Chỉ là một câu UPDATE, chạy trong
     * giao dịch của nơi gọi (lưu bệnh án, đổi phiếu khám sang COMPLETED và câu này cùng thành công hoặc cùng quay lui).
     * Trả về false nếu check-in không còn ở trong phòng. Sau khi giao dịch commit, nơi gọi gọi {@link #fillAfterCompletion}.
     */
    public boolean markCheckinCompleted(long checkinId, Instant now) {
        return jdbc.update("UPDATE checkins SET status = 'COMPLETED', completed_at = ?, seat_no = NULL WHERE id = ? AND status = 'IN_ROOM'",
                Tx.utc(now), checkinId) == 1;
    }

    /** Mời người kế tiếp vào ghế vừa trống nếu tự động mời đang bật. Phải gọi ngoài giao dịch, sau khi giao dịch hoàn thành đã commit. */
    public void fillAfterCompletion(LocalDate visitDate) {
        if (getAutoInvite()) fillSeatsAfterCommit(visitDate, List.of());
    }

    // ---------- Xem hàng đợi ----------

    private Map<Long, Patient> patientsById(Collection<Long> ids) {
        Map<Long, Patient> out = new HashMap<>();
        for (Patient p : patients.findAllById(ids)) out.put(p.getId(), p);
        return out;
    }

    private static Map<String, Object> checkinView(Checkin c, Appointment appt, Patient patient, Instant now) {
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
        return out;
    }

    public Map<String, Object> queueState(LocalDate date, Instant now) {
        List<Checkin> all = checkins.findByVisitDateAndStatusIn(date, LIVE);
        Map<Long, Appointment> apptMap = new HashMap<>();
        for (Appointment a : appointments.findAllById(all.stream().map(Checkin::getAppointmentId).toList())) apptMap.put(a.getId(), a);
        Map<Long, Patient> pMap = patientsById(all.stream().map(Checkin::getPatientId).toList());
        java.util.function.Function<Checkin, Map<String, Object>> view =
                c -> checkinView(c, apptMap.get(c.getAppointmentId()), pMap.get(c.getPatientId()), now);

        List<Checkin> inRoom = new ArrayList<>(all.stream().filter(c -> "IN_ROOM".equals(c.getStatus())).toList());
        inRoom.sort(Comparator.comparing(Checkin::getSeatNo));
        List<Checkin> waiting = new ArrayList<>(all.stream().filter(c -> "WAITING".equals(c.getStatus())).toList());
        sortWaiting(waiting);

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
        out.put("completedCount", all.stream().filter(c -> "COMPLETED".equals(c.getStatus())).count());
        out.put("slotMap", slotMap(date, all, apptMap, pMap));
        return out;
    }

    /** Sơ đồ ghế theo khung giờ: mỗi ô là một người đã check-in vào khung đó (UC.10.5). */
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
        Map<LocalDate, List<Checkin>> waitingByDate = new HashMap<>();
        for (Checkin c : found) if ("WAITING".equals(c.getStatus())) waitingByDate.computeIfAbsent(c.getVisitDate(), d -> new ArrayList<>()).add(c);
        Map<Long, Integer> order = new HashMap<>();
        for (List<Checkin> list : waitingByDate.values()) {
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
