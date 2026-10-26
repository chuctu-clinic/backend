import { Appointment, Checkin, MedicalRecord, Patient, SERVICE_TYPES, User } from '../models/index.js';
import { conflict, forbidden, notFound } from '../utils/errors.js';
import { slotDateStr, slotDateValue, todayVN } from '../utils/time.js';
import { completeCheckin } from './queue.service.js';

const EDITABLE = ['diagnosis', 'pulse', 'bloodPressure', 'medicalHistory', 'doctorName'];

/** Phiếu kết quả chỉ sửa được trong 24 giờ kể từ lúc xác nhận. */
export const EDIT_WINDOW_MS = 24 * 3600_000;

function toDate(s) {
  return s ? new Date(`${s}T00:00:00Z`) : null;
}

export function recordView(r, appt, patient) {
  return {
    id: r._id.toString(),
    appointmentId: r.appointmentId.toString(),
    patientId: r.patientId.toString(),
    visitDate: slotDateStr(r.visitDate),
    diagnosis: r.diagnosis,
    pulse: r.pulse,
    bloodPressure: r.bloodPressure,
    treatmentNotes: {
      acupuncture: r.treatmentNotes?.acupuncture ?? null,
      acupressure: r.treatmentNotes?.acupressure ?? null,
      other: r.treatmentNotes?.other ?? null,
    },
    medicalHistory: r.medicalHistory,
    followUpDate: r.followUpDate ? slotDateStr(r.followUpDate) : null,
    doctorName: r.doctorName,
    editHistory: (r.editHistory || []).map((h) => ({ editedByName: h.editedByName, editedAt: h.editedAt, changes: h.changes })),
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
    editableUntil: r.createdAt ? new Date(r.createdAt.getTime() + EDIT_WINDOW_MS) : null,
    appointment: appt
      ? { id: appt._id.toString(), ticketCode: appt.ticketCode, slotStart: appt.slotStart, serviceType: appt.serviceType, serviceLabel: SERVICE_TYPES[appt.serviceType] }
      : null,
    patient: patient
      ? {
          id: patient._id.toString(),
          fullName: patient.fullName,
          patientCode: patient.patientCode,
          dob: patient.dob,
          birthYear: patient.birthYear ?? (patient.dob ? patient.dob.getUTCFullYear() : null),
          phone: patient.phone,
        }
      : null,
  };
}

/** Lập phiếu kết quả cho người đang trong phòng khám; phiếu chuyển Đã hoàn thành (UC.9.7, UC.10.4). */
export async function createRecord(staff, appointmentId, data) {
  const appt = await Appointment.findById(appointmentId);
  if (!appt) throw notFound('Không tìm thấy phiếu khám.');
  if (appt.status !== 'IN_PROGRESS') {
    throw conflict('Chỉ lập phiếu kết quả cho người đang trong phòng khám.', 'NOT_IN_PROGRESS');
  }
  const patient = await Patient.findById(appt.patientId);
  if (patient.isLocked) throw forbidden('Hồ sơ đang bị khoá nên không ghi nhận lượt khám mới.');
  if (await MedicalRecord.exists({ appointmentId: appt._id })) throw conflict('Phiếu khám này đã có phiếu kết quả.', 'RECORD_EXISTS');

  const checkin = await Checkin.findOne({ appointmentId: appt._id, status: 'IN_ROOM' });
  const record = await MedicalRecord.create({
    patientId: appt.patientId,
    appointmentId: appt._id,
    checkinId: checkin?._id ?? null,
    visitDate: data.visitDate ? toDate(data.visitDate) : slotDateValue(todayVN()),
    diagnosis: data.diagnosis ?? null,
    pulse: data.pulse ?? null,
    bloodPressure: data.bloodPressure ?? null,
    treatmentNotes: data.treatmentNotes ?? {},
    medicalHistory: data.medicalHistory ?? null,
    followUpDate: toDate(data.followUpDate),
    doctorName: data.doctorName ?? null,
    recordedBy: staff._id,
  });
  await Appointment.updateOne({ _id: appt._id }, { $set: { status: 'COMPLETED' } });
  if (checkin) await completeCheckin(checkin._id);
  return recordView(record, appt, patient);
}

/** Sửa phiếu kết quả; ghi lại người sửa, thời điểm và nội dung thay đổi (UC.9.9). */
export async function updateRecord(staff, recordId, data) {
  const record = await MedicalRecord.findById(recordId);
  if (!record) throw notFound('Không tìm thấy phiếu kết quả.');
  const patient = await Patient.findById(record.patientId);
  if (patient.isLocked) throw forbidden('Hồ sơ đang bị khoá nên không sửa được phiếu kết quả.');
  if (Date.now() - record.createdAt.getTime() > EDIT_WINDOW_MS) {
    throw forbidden('Phiếu kết quả chỉ sửa được trong 24 giờ sau khi xác nhận.');
  }

  const changes = {};
  for (const key of EDITABLE) {
    if (data[key] !== undefined && (data[key] ?? null) !== (record[key] ?? null)) {
      changes[key] = { from: record[key] ?? null, to: data[key] ?? null };
      record[key] = data[key] ?? null;
    }
  }
  if (data.followUpDate !== undefined) {
    const before = record.followUpDate ? slotDateStr(record.followUpDate) : null;
    if ((data.followUpDate ?? null) !== before) {
      changes.followUpDate = { from: before, to: data.followUpDate ?? null };
      record.followUpDate = toDate(data.followUpDate);
    }
  }
  if (data.treatmentNotes) {
    for (const key of ['acupuncture', 'acupressure', 'other']) {
      const next = data.treatmentNotes[key] ?? null;
      const prev = record.treatmentNotes?.[key] ?? null;
      if (data.treatmentNotes[key] !== undefined && next !== prev) {
        changes[`treatmentNotes.${key}`] = { from: prev, to: next };
        record.set(`treatmentNotes.${key}`, next);
      }
    }
  }
  if (Object.keys(changes).length) {
    record.editHistory.push({ editedBy: staff._id, editedByName: staff.fullName, editedAt: new Date(), changes });
    await record.save();
  }
  const appt = await Appointment.findById(record.appointmentId);
  return recordView(record, appt, patient);
}

async function viewsFor(records) {
  const appts = await Appointment.find({ _id: { $in: records.map((r) => r.appointmentId) } });
  const patients = await Patient.find({ _id: { $in: records.map((r) => r.patientId) } });
  const aMap = new Map(appts.map((a) => [a._id.toString(), a]));
  const pMap = new Map(patients.map((p) => [p._id.toString(), p]));
  const staff = await User.find({ _id: { $in: records.map((r) => r.recordedBy) } }).select('fullName').lean();
  const sMap = new Map(staff.map((u) => [u._id.toString(), u.fullName]));
  return records.map((r) => ({
    ...recordView(r, aMap.get(r.appointmentId.toString()), pMap.get(r.patientId.toString())),
    recordedByName: sMap.get(r.recordedBy?.toString()) ?? null,
  }));
}

export async function getRecord(recordId) {
  const record = await MedicalRecord.findById(recordId);
  if (!record) throw notFound('Không tìm thấy phiếu kết quả.');
  return (await viewsFor([record]))[0];
}

export async function getRecordByAppointment(appointmentId) {
  const record = await MedicalRecord.findOne({ appointmentId });
  return record ? (await viewsFor([record]))[0] : null;
}

/** Ngày khám gần nhất của từng hồ sơ, dùng cho danh sách hồ sơ bệnh nhân. */
export async function lastVisitByPatient(patientIds) {
  const rows = await MedicalRecord.aggregate([
    { $match: { patientId: { $in: patientIds } } },
    { $group: { _id: '$patientId', last: { $max: '$visitDate' } } },
  ]);
  return new Map(rows.map((r) => [r._id.toString(), slotDateStr(r.last)]));
}

/** Lịch sử khám chỉ gồm các lượt thuộc hồ sơ đang tra cứu (BR.39). */
export async function listPatientRecords(patientId) {
  return viewsFor(await MedicalRecord.find({ patientId }).sort({ visitDate: -1, createdAt: -1 }));
}

// ---------- Người dùng ----------

async function ownPatientIds(userId) {
  return (await Patient.find({ userId }).select('_id').lean()).map((p) => p._id);
}

/** Kết quả khám chỉ hiện sau khi phòng khám đã ghi nhận (BR.21) và thuộc hồ sơ của tài khoản (BR.20). */
export async function getUserResult(user, appointmentId) {
  const ids = await ownPatientIds(user._id);
  const record = await MedicalRecord.findOne({ appointmentId, patientId: { $in: ids } });
  if (!record) throw notFound('Phiếu khám này chưa có kết quả.');
  const [{ editHistory, recordedByName, ...view }] = await viewsFor([record]);
  return view;
}

export async function listUserHistory(user, { patientId } = {}) {
  let ids = await ownPatientIds(user._id);
  if (patientId) ids = ids.filter((id) => id.toString() === patientId);
  const views = await viewsFor(await MedicalRecord.find({ patientId: { $in: ids } }).sort({ visitDate: -1 }));
  return views.map(({ editHistory, recordedByName, ...v }) => v);
}
