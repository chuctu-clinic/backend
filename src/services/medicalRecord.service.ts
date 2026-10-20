import type { Types } from 'mongoose';
import {
  Appointment,
  Checkin,
  MedicalRecord,
  User,
  Patient,
  SERVICE_TYPES,
  TREATMENT_KEYS,
  type AppointmentDoc,
  type MedicalRecordDoc,
  type PatientDoc,
  type RecordChanges,
  type TreatmentKey,
  type UserDoc,
} from '../models/index.ts';
import { conflict, forbidden, notFound } from '../utils/errors.ts';
import { slotDateStr, slotDateValue, toDate, todayVN } from '../utils/time.ts';
import { completeCheckin } from './queue.service.ts';

const EDITABLE = ['diagnosis', 'pulse', 'bloodPressure', 'medicalHistory', 'doctorName'] as const;

/** Nội dung phiếu kết quả; ngày ở dạng YYYY-MM-DD. */
export interface RecordInput {
  visitDate?: string;
  diagnosis?: string | null;
  pulse?: string | null;
  bloodPressure?: string | null;
  treatmentNotes?: Partial<Record<TreatmentKey, string | null>>;
  medicalHistory?: string | null;
  followUpDate?: string | null;
  doctorName?: string | null;
}

export function recordView(
  r: MedicalRecordDoc,
  appt: AppointmentDoc | null | undefined,
  patient: PatientDoc | null | undefined,
  recordedByName: string | null = null,
) {
  return {
    recordedByName,
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
    editHistory: (r.editHistory || []).map((h) => ({ editedByName: h.editedByName, editedAt: h.editedAt, changes: h.changes as RecordChanges })),
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
    appointment: appt
      ? { id: appt._id.toString(), ticketCode: appt.ticketCode, slotStart: appt.slotStart, serviceType: appt.serviceType, serviceLabel: SERVICE_TYPES[appt.serviceType] }
      : null,
    patient: patient ? { id: patient._id.toString(), fullName: patient.fullName, patientCode: patient.patientCode } : null,
  };
}

/** Lập phiếu kết quả cho người đang trong phòng khám; phiếu chuyển Đã hoàn thành (UC.9.7, UC.10.4). */
export async function createRecord(staff: UserDoc, appointmentId: string, data: RecordInput) {
  const appt = await Appointment.findById(appointmentId);
  if (!appt) throw notFound('Không tìm thấy phiếu khám.');
  if (appt.status !== 'IN_PROGRESS') {
    throw conflict('Chỉ lập phiếu kết quả cho người đang trong phòng khám.', 'NOT_IN_PROGRESS');
  }
  const patient = await Patient.findById(appt.patientId);
  if (patient?.isLocked) throw forbidden('Hồ sơ đang bị khoá nên không ghi nhận lượt khám mới.');
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
  return recordView(record, appt, patient, await recorderName(record));
}

/** Sửa phiếu kết quả; ghi lại người sửa, thời điểm và nội dung thay đổi (UC.9.9). */
export async function updateRecord(staff: UserDoc, recordId: string, data: RecordInput) {
  const record = await MedicalRecord.findById(recordId);
  if (!record) throw notFound('Không tìm thấy phiếu kết quả.');
  const patient = await Patient.findById(record.patientId);
  if (patient?.isLocked) throw forbidden('Hồ sơ đang bị khoá nên không sửa được phiếu kết quả.');

  const changes: RecordChanges = {};
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
    for (const key of TREATMENT_KEYS) {
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
  return recordView(record, appt, patient, await recorderName(record));
}

/** Tên người đã lập phiếu kết quả. */
async function recorderName(record: MedicalRecordDoc) {
  return (await User.findById(record.recordedBy).select('fullName').lean())?.fullName ?? null;
}

async function viewsFor(records: MedicalRecordDoc[]) {
  const [appts, patients, users] = await Promise.all([
    Appointment.find({ _id: { $in: records.map((r) => r.appointmentId) } }),
    Patient.find({ _id: { $in: records.map((r) => r.patientId) } }),
    User.find({ _id: { $in: records.map((r) => r.recordedBy) } }).select('fullName').lean(),
  ]);
  const aMap = new Map(appts.map((a) => [a._id.toString(), a]));
  const pMap = new Map(patients.map((p) => [p._id.toString(), p]));
  const uMap = new Map(users.map((u) => [u._id.toString(), u.fullName]));
  return records.map((r) =>
    recordView(r, aMap.get(r.appointmentId.toString()), pMap.get(r.patientId.toString()), uMap.get(r.recordedBy.toString()) ?? null),
  );
}

export async function getRecord(recordId: string) {
  const record = await MedicalRecord.findById(recordId);
  if (!record) throw notFound('Không tìm thấy phiếu kết quả.');
  return (await viewsFor([record]))[0];
}

export async function getRecordByAppointment(appointmentId: string) {
  const record = await MedicalRecord.findOne({ appointmentId });
  return record ? (await viewsFor([record]))[0] : null;
}

/** Lịch sử khám chỉ gồm các lượt thuộc hồ sơ đang tra cứu (BR.39). */
export async function listPatientRecords(patientId: Types.ObjectId) {
  return viewsFor(await MedicalRecord.find({ patientId }).sort({ visitDate: -1, createdAt: -1 }));
}

// ---------- Người dùng ----------

async function ownPatientIds(userId: Types.ObjectId) {
  return (await Patient.find({ userId }).select('_id').lean()).map((p) => p._id);
}

/** Kết quả khám chỉ hiện sau khi phòng khám đã ghi nhận (BR.21) và thuộc hồ sơ của tài khoản (BR.20). */
export async function getUserResult(user: UserDoc, appointmentId: string) {
  const ids = await ownPatientIds(user._id);
  const record = await MedicalRecord.findOne({ appointmentId, patientId: { $in: ids } });
  if (!record) throw notFound('Phiếu khám này chưa có kết quả.');
  const [{ editHistory, ...view }] = await viewsFor([record]);
  return view;
}

export async function listUserHistory(user: UserDoc, { patientId }: { patientId?: string } = {}) {
  let ids = await ownPatientIds(user._id);
  if (patientId) ids = ids.filter((id) => id.toString() === patientId);
  const views = await viewsFor(await MedicalRecord.find({ patientId: { $in: ids } }).sort({ visitDate: -1 }));
  return views.map(({ editHistory, ...v }) => v);
}
