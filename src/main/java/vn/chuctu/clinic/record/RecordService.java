package vn.chuctu.clinic.record;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.account.UserRepository;
import vn.chuctu.clinic.appointment.Appointment;
import vn.chuctu.clinic.appointment.AppointmentRepository;
import vn.chuctu.clinic.common.Constants;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Opt;
import vn.chuctu.clinic.common.Tx;
import vn.chuctu.clinic.common.VnTime;
import vn.chuctu.clinic.patient.Patient;
import vn.chuctu.clinic.patient.PatientRepository;
import vn.chuctu.clinic.patient.PatientService;
import vn.chuctu.clinic.queue.Checkin;
import vn.chuctu.clinic.queue.CheckinRepository;
import vn.chuctu.clinic.queue.QueueService;
import vn.chuctu.clinic.record.RecordForms.RecordData;

/**
 * Bệnh án, tức phiếu kết quả khám (services/medicalRecord.service.js).
 *
 * <p>Lập phiếu là một giao dịch: lưu bệnh án, đổi phiếu khám sang COMPLETED và hoàn thành check-in (nhả ghế) cùng thành công
 * hoặc cùng quay lui. Mời người kế tiếp vào ghế vừa trống chạy sau khi giao dịch đã commit, ngoài mọi giao dịch, vì mỗi lần thử
 * một ghế của hàng đợi là giao dịch riêng và không thấy dòng chưa commit.
 *
 * <p>Khung giờ sửa 24 giờ tính theo {@link Clock} (chỗ Node dùng Date.now). Không gọi các hàm ghi từ trong một giao dịch khác (xem {@link Tx}).
 */
@Service
public class RecordService {

    private static final Logger log = LoggerFactory.getLogger(RecordService.class);

    /** Phiếu kết quả chỉ sửa được trong 24 giờ kể từ lúc xác nhận. */
    public static final Duration EDIT_WINDOW = Duration.ofHours(24);

    private static final String NOT_FOUND = "Không tìm thấy phiếu kết quả.";

    private final MedicalRecordRepository records;
    private final MedicalRecordEditRepository edits;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final UserRepository users;
    private final CheckinRepository checkins;
    private final QueueService queue;
    private final JdbcTemplate jdbc;
    private final Tx tx;
    private final Clock clock;

    public RecordService(MedicalRecordRepository records, MedicalRecordEditRepository edits, AppointmentRepository appointments,
            PatientRepository patients, UserRepository users, CheckinRepository checkins, QueueService queue, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.records = records;
        this.edits = edits;
        this.appointments = appointments;
        this.patients = patients;
        this.users = users;
        this.checkins = checkins;
        this.queue = queue;
        this.jdbc = jdbc;
        this.tx = new Tx(transactionManager);
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    // ---------- Hình dạng trả về giao diện ----------

    /** recordView của Node. {@code appt} và {@code patient} có thể null. */
    private Map<String, Object> recordView(MedicalRecord r, Appointment appt, Patient patient, List<MedicalRecordEdit> history) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(r.getId()));
        out.put("appointmentId", String.valueOf(r.getAppointmentId()));
        out.put("patientId", String.valueOf(r.getPatientId()));
        out.put("visitDate", r.getVisitDate().toString());
        out.put("diagnosis", r.getDiagnosis());
        out.put("pulse", r.getPulse());
        out.put("bloodPressure", r.getBloodPressure());
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("acupuncture", r.getTreatmentAcupuncture());
        notes.put("acupressure", r.getTreatmentAcupressure());
        notes.put("other", r.getTreatmentOther());
        out.put("treatmentNotes", notes);
        out.put("medicalHistory", r.getMedicalHistory());
        out.put("followUpDate", r.getFollowUpDate() == null ? null : r.getFollowUpDate().toString());
        out.put("doctorName", r.getDoctorName());
        List<Map<String, Object>> editHistory = new ArrayList<>();
        for (MedicalRecordEdit h : history) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("editedByName", h.getEditedByName());
            item.put("editedAt", h.getEditedAt());
            item.put("changes", h.getChanges());
            editHistory.add(item);
        }
        out.put("editHistory", editHistory);
        out.put("createdAt", r.getCreatedAt());
        out.put("updatedAt", r.getUpdatedAt());
        out.put("editableUntil", r.getCreatedAt() == null ? null : r.getCreatedAt().plus(EDIT_WINDOW));
        if (appt == null) {
            out.put("appointment", null);
        } else {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", String.valueOf(appt.getId()));
            a.put("ticketCode", appt.getTicketCode());
            a.put("slotStart", appt.getSlotStart());
            a.put("serviceType", appt.getServiceType());
            a.put("serviceLabel", Constants.SERVICE_TYPES.get(appt.getServiceType()));
            out.put("appointment", a);
        }
        if (patient == null) {
            out.put("patient", null);
        } else {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", String.valueOf(patient.getId()));
            p.put("fullName", patient.getFullName());
            p.put("patientCode", patient.getPatientCode());
            p.put("dob", PatientService.midnightUtc(patient.getDob()));
            p.put("birthYear", patient.getBirthYear() != null ? patient.getBirthYear()
                    : patient.getDob() != null ? Integer.valueOf(patient.getDob().getYear()) : null);
            p.put("phone", patient.getPhone());
            out.put("patient", p);
        }
        return out;
    }

    /** viewsFor của Node: thêm tên người ghi (recordedByName). Gom dữ liệu liên quan theo lô. */
    private List<Map<String, Object>> viewsFor(List<MedicalRecord> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (list.isEmpty()) return out;
        Map<Long, Appointment> apptMap = new HashMap<>();
        for (Appointment a : appointments.findAllById(list.stream().map(MedicalRecord::getAppointmentId).toList())) apptMap.put(a.getId(), a);
        Map<Long, Patient> patientMap = new HashMap<>();
        for (Patient p : patients.findAllById(list.stream().map(MedicalRecord::getPatientId).toList())) patientMap.put(p.getId(), p);
        Map<Long, String> staffNames = new HashMap<>();
        for (User u : users.findAllById(list.stream().map(MedicalRecord::getRecordedBy).toList())) staffNames.put(u.getId(), u.getFullName());
        Map<Long, List<MedicalRecordEdit>> history = new HashMap<>();
        for (MedicalRecordEdit e : edits.findByMedicalRecordIdInOrderByIdAsc(list.stream().map(MedicalRecord::getId).toList())) {
            history.computeIfAbsent(e.getMedicalRecordId(), k -> new ArrayList<>()).add(e);
        }
        for (MedicalRecord r : list) {
            Map<String, Object> view = recordView(r, apptMap.get(r.getAppointmentId()), patientMap.get(r.getPatientId()),
                    history.getOrDefault(r.getId(), List.of()));
            view.put("recordedByName", staffNames.get(r.getRecordedBy()));
            out.add(view);
        }
        return out;
    }

    // ---------- Lập và sửa ----------

    /** Lập phiếu kết quả cho người đang trong phòng khám; phiếu chuyển Đã hoàn thành (UC.9.7, UC.10.4). */
    public Map<String, Object> createRecord(User staff, long appointmentId, RecordData data) {
        Appointment appt = appointments.findById(appointmentId).orElseThrow(() -> Errors.notFound("Không tìm thấy phiếu khám."));
        if (!"IN_PROGRESS".equals(appt.getStatus())) {
            throw Errors.conflict("Chỉ lập phiếu kết quả cho người đang trong phòng khám.", "NOT_IN_PROGRESS");
        }
        Patient patient = patients.findById(appt.getPatientId()).orElseThrow(() -> Errors.notFound("Không tìm thấy hồ sơ bệnh nhân."));
        if (patient.isLocked()) throw Errors.forbidden("Hồ sơ đang bị khoá nên không ghi nhận lượt khám mới.");
        if (records.existsByAppointmentId(appt.getId())) throw recordExists();

        Checkin checkin = checkins.findFirstByAppointmentIdAndStatus(appt.getId(), "IN_ROOM").orElse(null);
        Instant now = now();
        MedicalRecord record = new MedicalRecord();
        record.setPatientId(appt.getPatientId());
        record.setAppointmentId(appt.getId());
        record.setCheckinId(checkin == null ? null : checkin.getId());
        record.setVisitDate(data.visitDate().get() != null ? data.visitDate().get() : VnTime.today(now));
        record.setDiagnosis(data.diagnosis().get());
        record.setPulse(data.pulse().get());
        record.setBloodPressure(data.bloodPressure().get());
        record.setTreatmentAcupuncture(data.acupuncture().get());
        record.setTreatmentAcupressure(data.acupressure().get());
        record.setTreatmentOther(data.other().get());
        record.setMedicalHistory(data.medicalHistory().get());
        record.setFollowUpDate(data.followUpDate().get());
        record.setDoctorName(data.doctorName().get());
        record.setRecordedBy(staff.getId());

        MedicalRecord saved;
        try {
            saved = tx.run(() -> {
                // Thứ tự khoá cố định: phiếu khám, check-in, rồi mới đến bệnh án
                int moved = jdbc.update("UPDATE appointments SET status = 'COMPLETED', updated_at = ? WHERE id = ? AND status = 'IN_PROGRESS'",
                        Tx.utc(now), appt.getId());
                if (moved != 1) throw Errors.conflict("Chỉ lập phiếu kết quả cho người đang trong phòng khám.", "NOT_IN_PROGRESS");
                if (checkin != null && !queue.markCheckinCompleted(checkin.getId(), now)) {
                    throw Errors.conflict("Phiếu khám vừa thay đổi trạng thái, vui lòng tải lại.", "STALE");
                }
                return records.saveAndFlush(record);
            });
        } catch (DataIntegrityViolationException e) {
            if (records.existsByAppointmentId(appt.getId())) throw recordExists();
            throw e;
        }
        // Giao dịch đã commit: ghế vừa trống nhận người kế tiếp (BR.30)
        if (checkin != null) {
            try {
                queue.fillAfterCompletion(checkin.getVisitDate());
            } catch (RuntimeException e) {
                // Bệnh án đã lưu; lỗi lấp ghế không được làm yêu cầu báo thất bại. Lần check-in hoặc mời kế tiếp sẽ lấp lại.
                log.warn("Không mời được người kế tiếp sau khi lưu bệnh án {}", saved.getId(), e);
            }
        }
        return recordView(saved, appt, patient, List.of());
    }

    private static RuntimeException recordExists() {
        return Errors.conflict("Phiếu khám này đã có phiếu kết quả.", "RECORD_EXISTS");
    }

    /** Sửa phiếu kết quả; ghi lại người sửa, thời điểm và nội dung thay đổi (UC.9.9). */
    public Map<String, Object> updateRecord(User staff, long recordId, RecordData data) {
        MedicalRecord peek = records.findById(recordId).orElseThrow(() -> Errors.notFound(NOT_FOUND));
        Patient patient = patients.findById(peek.getPatientId()).orElseThrow(() -> Errors.notFound("Không tìm thấy hồ sơ bệnh nhân."));
        if (patient.isLocked()) throw Errors.forbidden("Hồ sơ đang bị khoá nên không sửa được phiếu kết quả.");

        MedicalRecord record = tx.run(() -> {
            // Khoá dòng: hai người sửa cùng lúc xếp hàng và mỗi người so với giá trị mới nhất
            MedicalRecord r = records.findForUpdate(recordId).orElseThrow(() -> Errors.notFound(NOT_FOUND));
            Instant now = now();
            if (now.toEpochMilli() - r.getCreatedAt().toEpochMilli() > EDIT_WINDOW.toMillis()) {
                throw Errors.forbidden("Phiếu kết quả chỉ sửa được trong 24 giờ sau khi xác nhận.");
            }
            Map<String, Object> changes = new LinkedHashMap<>();
            if (change(changes, "diagnosis", data.diagnosis(), r.getDiagnosis())) r.setDiagnosis(data.diagnosis().get());
            if (change(changes, "pulse", data.pulse(), r.getPulse())) r.setPulse(data.pulse().get());
            if (change(changes, "bloodPressure", data.bloodPressure(), r.getBloodPressure())) r.setBloodPressure(data.bloodPressure().get());
            if (change(changes, "medicalHistory", data.medicalHistory(), r.getMedicalHistory())) r.setMedicalHistory(data.medicalHistory().get());
            if (change(changes, "doctorName", data.doctorName(), r.getDoctorName())) r.setDoctorName(data.doctorName().get());
            if (data.followUpDate().isPresent()) {
                LocalDate next = data.followUpDate().get();
                if (!Objects.equals(next, r.getFollowUpDate())) {
                    changes.put("followUpDate", fromTo(r.getFollowUpDate() == null ? null : r.getFollowUpDate().toString(),
                            next == null ? null : next.toString()));
                    r.setFollowUpDate(next);
                }
            }
            if (change(changes, "treatmentNotes.acupuncture", data.acupuncture(), r.getTreatmentAcupuncture())) {
                r.setTreatmentAcupuncture(data.acupuncture().get());
            }
            if (change(changes, "treatmentNotes.acupressure", data.acupressure(), r.getTreatmentAcupressure())) {
                r.setTreatmentAcupressure(data.acupressure().get());
            }
            if (change(changes, "treatmentNotes.other", data.other(), r.getTreatmentOther())) r.setTreatmentOther(data.other().get());

            if (!changes.isEmpty()) {
                MedicalRecordEdit edit = new MedicalRecordEdit();
                edit.setMedicalRecordId(r.getId());
                edit.setEditedBy(staff.getId());
                edit.setEditedByName(staff.getFullName());
                edit.setEditedAt(now);
                edit.setChanges(changes);
                edits.save(edit);
                r = records.saveAndFlush(r);
            }
            return r;
        });
        Appointment appt = appointments.findById(record.getAppointmentId()).orElse(null);
        return recordView(record, appt, patient, edits.findByMedicalRecordIdInOrderByIdAsc(List.of(record.getId())));
    }

    /** Ghi một thay đổi vào {@code changes} nếu ô được gửi và khác giá trị cũ. Trả về true khi cần cập nhật. */
    private static boolean change(Map<String, Object> changes, String key, Opt<String> next, String current) {
        if (next.isAbsent() || Objects.equals(next.get(), current)) return false;
        changes.put(key, fromTo(current, next.get()));
        return true;
    }

    private static Map<String, Object> fromTo(Object from, Object to) {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("from", from);
        pair.put("to", to);
        return pair;
    }

    // ---------- Xem (nhân viên) ----------

    @Transactional(readOnly = true)
    public Map<String, Object> getRecord(long recordId) {
        MedicalRecord record = records.findById(recordId).orElseThrow(() -> Errors.notFound(NOT_FOUND));
        return viewsFor(List.of(record)).get(0);
    }

    /** Bệnh án của một phiếu khám, null nếu chưa có. */
    @Transactional(readOnly = true)
    public Map<String, Object> getRecordByAppointment(long appointmentId) {
        return records.findByAppointmentId(appointmentId).map(r -> viewsFor(List.of(r)).get(0)).orElse(null);
    }

    /** Lịch sử khám chỉ gồm các lượt thuộc hồ sơ đang tra cứu (BR.39). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPatientRecords(long patientId) {
        return viewsFor(records.findByPatientIdOrderByVisitDateDescCreatedAtDescIdDesc(patientId));
    }

    // ---------- Người dùng ----------

    private static Map<String, Object> forUser(Map<String, Object> view) {
        view.remove("editHistory");
        view.remove("recordedByName");
        return view;
    }

    /** Kết quả khám chỉ hiện sau khi phòng khám đã ghi nhận (BR.21) và thuộc hồ sơ của tài khoản (BR.20). */
    @Transactional(readOnly = true)
    public Map<String, Object> getUserResult(User user, long appointmentId) {
        MedicalRecord record = records.findOwnedByAppointment(appointmentId, user.getId())
                .orElseThrow(() -> Errors.notFound("Phiếu khám này chưa có kết quả."));
        return forUser(viewsFor(List.of(record)).get(0));
    }

    /** Lịch sử khám của các hồ sơ thuộc tài khoản; patientId (nếu có) lọc theo một hồ sơ. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUserHistory(User user, String patientId) {
        Long only = null;
        if (patientId != null && !patientId.isEmpty()) {
            try {
                only = Long.parseLong(patientId);
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return viewsFor(records.listOwned(user.getId(), only)).stream().map(RecordService::forUser).toList();
    }
}
