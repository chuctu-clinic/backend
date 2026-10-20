import type { FilterQuery, Types } from 'mongoose';
import { rules } from '../config.ts';
import { STATUS_LABELS, type AppointmentStatus, type ServiceType, type UserStatus } from '../shared/constants.ts';
import {
  ACTIVE_STATUSES,
  Appointment,
  Checkin,
  MedicalRecord,
  Patient,
  SERVICE_TYPES,
  SlotCounter,
  nextSeq,
  type AppointmentDoc,
  type AppointmentSource,
  type PatientDoc,
  type UserDoc,
} from '../models/index.ts';
import { badRequest, conflict, forbidden, mongoErrorCode, notFound } from '../utils/errors.ts';
import { escapeRegex } from '../utils/validate.ts';
import { waitingOrder } from './queue.service.ts';
import {
  ddmm,
  diffDays,
  hhmmToMinutes,
  minutesOfDayVN,
  slotDateStr,
  slotDateValue,
  slotInstant,
  todayVN,
  yy,
} from '../utils/time.ts';

const { slotCapacity, slots, slotMinutes } = rules.booking;

export { STATUS_LABELS };

/** USER: người dùng tự đặt trên website; COUNTER: tiếp tân đặt hộ tại quầy. */
export type BookingMode = 'USER' | 'COUNTER';

// Người dùng chỉ thấy ba trạng thái: Đã đặt lịch, Đã hoàn thành, Đã huỷ (BR.17)
export function userStatus(status: AppointmentStatus): UserStatus {
  if (status === 'COMPLETED') return 'COMPLETED';
  if (status === 'CANCELLED') return 'CANCELLED';
  return 'BOOKED';
}

export const COUNTER_CANCEL_REASONS = ['Bệnh nhân xin huỷ', 'Không đến được', 'Đặt nhầm', 'Khác'];

const isStatus = (s: string | undefined): s is AppointmentStatus => !!s && s in STATUS_LABELS;

// ---------- Khung giờ ----------

/**
 * Lý do một khung giờ không chọn được, hoặc null nếu chọn được.
 * mode USER: hôm nay tới 30 ngày tới, khung chưa bắt đầu (BR.13, BR.14).
 * mode COUNTER: hôm nay tới 7 ngày tới, khung chưa kết thúc (tiếp tân đặt hộ được khung đang diễn ra).
 */
export function slotTimeIssue(dateStr: string, start: string, mode: BookingMode, now = new Date()) {
  if (!slots.includes(start)) return 'Khung giờ không hợp lệ.';
  const today = todayVN(now);
  const days = diffDays(today, dateStr);
  const maxDays = mode === 'COUNTER' ? rules.booking.counterMaxDays : rules.booking.userMaxDays;
  if (days < 0) return 'Ngày khám đã qua.';
  if (days > maxDays) return `Chỉ đặt được lịch trong vòng ${maxDays} ngày tới.`;
  if (days === 0) {
    const nowMin = minutesOfDayVN(now);
    const startMin = hhmmToMinutes(start);
    if (mode === 'COUNTER' ? startMin + slotMinutes <= nowMin : startMin <= nowMin) {
      return mode === 'COUNTER' ? 'Khung giờ đã kết thúc.' : 'Khung giờ đã bắt đầu.';
    }
  }
  return null;
}

export async function getAvailability(dateStr: string, mode: BookingMode, now = new Date()) {
  const counters = await SlotCounter.find({ date: dateStr }).lean();
  const byStart = Object.fromEntries(counters.map((c) => [c.start, c.count]));
  return slots.map((start) => {
    const booked = byStart[start] || 0;
    const timeIssue = slotTimeIssue(dateStr, start, mode, now);
    const full = booked >= slotCapacity;
    return {
      start,
      end: minutesToHHMM(hhmmToMinutes(start) + slotMinutes),
      booked,
      capacity: slotCapacity,
      remaining: Math.max(0, slotCapacity - booked),
      available: !timeIssue && !full,
      reason: timeIssue ? (timeIssue.includes('bắt đầu') ? 'Đã bắt đầu' : timeIssue) : full ? 'Đã đủ chỗ' : null,
    };
  });
}

function minutesToHHMM(min: number) {
  return `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`;
}

/** Giữ một chỗ trong khung giờ; thất bại nếu đã đủ 6 phiếu. */
async function reserveSlot(dateStr: string, start: string) {
  try {
    await SlotCounter.findOneAndUpdate(
      { date: dateStr, start, count: { $lt: slotCapacity } },
      { $inc: { count: 1 } },
      { upsert: true, new: true },
    );
  } catch (err) {
    // Khung đã đầy: điều kiện count < 6 không khớp nên upsert đụng khoá duy nhất (date, start)
    if (mongoErrorCode(err) === 11000) {
      throw conflict('Khung giờ này vừa đủ chỗ. Cô chú vui lòng chọn khung giờ khác.', 'SLOT_FULL');
    }
    throw err;
  }
}

async function releaseSlot(dateStr: string, start: string) {
  await SlotCounter.updateOne({ date: dateStr, start, count: { $gt: 0 } }, { $inc: { count: -1 } });
}

/** Mã phiếu khám dạng CT-PK-26-2809-004: năm, ngày tháng khám, số thứ tự trong ngày. */
async function nextTicketCode(dateStr: string) {
  const seq = await nextSeq(`ticket-${dateStr}`);
  return `CT-PK-${yy(dateStr)}-${ddmm(dateStr)}-${String(seq).padStart(3, '0')}`;
}

// ---------- Tạo phiếu ----------

interface NewAppointment {
  patient: PatientDoc;
  serviceType: ServiceType;
  dateStr: string;
  start: string;
  source: AppointmentSource;
  userId: Types.ObjectId | null;
  createdBy: Types.ObjectId;
  rescheduledFromId?: Types.ObjectId | null;
  mode: BookingMode;
}

async function createAppointment({ patient, serviceType, dateStr, start, source, userId, createdBy, rescheduledFromId, mode }: NewAppointment) {
  if (!SERVICE_TYPES[serviceType]) throw badRequest('Nội dung khám không hợp lệ.', { fields: { serviceType: 'Cô chú vui lòng chọn nội dung khám.' } });
  if (patient.isLocked) {
    throw forbidden('Hồ sơ này đang bị phòng khám khoá nên không đặt lịch được. Cô chú gọi phòng khám để được hỗ trợ.');
  }
  const issue = slotTimeIssue(dateStr, start, mode);
  if (issue) throw badRequest(issue, { fields: { slotStart: issue } }, 'SLOT_UNAVAILABLE');

  const dup = await Appointment.exists({
    patientId: patient._id,
    slotDate: slotDateValue(dateStr),
    status: { $in: ACTIVE_STATUSES },
  });
  if (dup) {
    throw conflict(`${patient.fullName} đã có phiếu khám trong ngày này. Cô chú xem lại mục Phiếu khám bệnh.`, 'ALREADY_BOOKED');
  }

  await reserveSlot(dateStr, start);
  try {
    return await Appointment.create({
      ticketCode: await nextTicketCode(dateStr),
      userId,
      patientId: patient._id,
      serviceType,
      slotDate: slotDateValue(dateStr),
      slotStart: start,
      expectedTime: slotInstant(dateStr, start),
      status: 'BOOKED',
      source,
      createdBy,
      rescheduledFromId: rescheduledFromId ?? null,
    });
  } catch (err) {
    await releaseSlot(dateStr, start);
    throw err;
  }
}

export interface BookingInput {
  patientId: string;
  serviceType: ServiceType;
  slotDate: string;
  slotStart: string;
  rebookFromId?: string | null;
}

/** Người dùng đặt lịch cho hồ sơ thuộc tài khoản của mình (UC.6.3). */
export async function userBook(user: UserDoc, { patientId, serviceType, slotDate, slotStart, rebookFromId }: BookingInput) {
  const patient = await Patient.findOne({ _id: patientId, userId: user._id });
  if (!patient) throw notFound('Không tìm thấy hồ sơ người đi khám.');
  if (!patient.relationship) {
    throw badRequest('Cô chú vui lòng khai quan hệ với người đi khám trong hồ sơ.', { fields: { relationship: 'Chưa khai quan hệ.' } });
  }
  let rescheduledFromId = null;
  if (rebookFromId) {
    const old = await Appointment.findOne({ _id: rebookFromId, userId: user._id, status: 'CANCELLED' });
    if (old) rescheduledFromId = old._id;
  }
  return createAppointment({
    patient,
    serviceType,
    dateStr: slotDate,
    start: slotStart,
    source: 'ONLINE',
    userId: user._id,
    createdBy: user._id,
    rescheduledFromId,
    mode: 'USER',
  });
}

/** Tiếp tân đặt hộ tại quầy (UC.7.5). */
export async function counterBook(staff: UserDoc, { patientId, serviceType, slotDate, slotStart }: BookingInput) {
  const patient = await Patient.findById(patientId);
  if (!patient) throw notFound('Không tìm thấy hồ sơ bệnh nhân.');
  return createAppointment({
    patient,
    serviceType,
    dateStr: slotDate,
    start: slotStart,
    source: 'COUNTER',
    userId: patient.userId ?? null,
    createdBy: staff._id,
    mode: 'COUNTER',
  });
}

// ---------- Huỷ, đổi giờ ----------

async function cancelInternal(appt: AppointmentDoc, by: UserDoc, reason: string | null | undefined) {
  if (appt.status === 'COMPLETED') throw conflict('Phiếu khám đã hoàn thành nên không huỷ được.', 'NOT_CANCELLABLE');
  if (appt.status === 'CANCELLED') throw conflict('Phiếu khám này đã được huỷ trước đó.', 'NOT_CANCELLABLE');
  const updated = await Appointment.findOneAndUpdate(
    { _id: appt._id, status: { $in: ACTIVE_STATUSES } },
    { $set: { status: 'CANCELLED', cancelReason: reason || null, cancelledBy: by._id, cancelledAt: new Date() } },
    { new: true },
  );
  if (!updated) throw conflict('Phiếu khám vừa thay đổi trạng thái, cô chú tải lại trang.', 'STALE');
  // Người đã check-in mà huỷ phiếu thì rời hàng đợi
  await Checkin.updateMany({ appointmentId: appt._id, status: { $in: ['WAITING', 'IN_ROOM'] } }, { $set: { status: 'REVERTED', seatNo: null } });
  await releaseSlot(slotDateStr(appt.slotDate), appt.slotStart);
  return updated;
}

export async function userCancel(user: UserDoc, appointmentId: string, reason: string | null) {
  const appt = await Appointment.findOne({ _id: appointmentId, userId: user._id });
  if (!appt) throw notFound('Không tìm thấy phiếu khám.');
  return cancelInternal(appt, user, reason);
}

export async function counterCancel(staff: UserDoc, appointmentId: string, reason: string) {
  if (!reason) throw badRequest('Vui lòng chọn lý do huỷ.', { fields: { reason: 'Vui lòng chọn lý do huỷ.' } });
  const appt = await Appointment.findById(appointmentId);
  if (!appt) throw notFound('Không tìm thấy phiếu khám.');
  return cancelInternal(appt, staff, reason);
}

/** Đổi giờ tính như đặt mới vào khung mới: phiếu cũ huỷ, phiếu mới ghi rescheduledFromId (BR.26). */
export async function reschedule(staff: UserDoc, appointmentId: string, { slotDate, slotStart }: { slotDate: string; slotStart: string }) {
  const old = await Appointment.findById(appointmentId);
  if (!old) throw notFound('Không tìm thấy phiếu khám.');
  if (old.status !== 'BOOKED') throw conflict('Chỉ đổi giờ được phiếu chưa check-in.', 'NOT_RESCHEDULABLE');
  if (slotDateStr(old.slotDate) === slotDate && old.slotStart === slotStart) {
    throw badRequest('Khung giờ mới trùng khung giờ cũ.');
  }
  const patient = await Patient.findById(old.patientId);
  const issue = slotTimeIssue(slotDate, slotStart, 'COUNTER');
  if (issue) throw badRequest(issue, { fields: { slotStart: issue } }, 'SLOT_UNAVAILABLE');
  if (patient?.isLocked) throw forbidden('Hồ sơ đang bị khoá nên không đặt lịch được.');

  // Giữ chỗ khung mới trước, rồi mới huỷ phiếu cũ, để không mất chỗ cũ khi khung mới đã đầy
  await reserveSlot(slotDate, slotStart);
  let created: AppointmentDoc;
  try {
    created = await Appointment.create({
      ticketCode: await nextTicketCode(slotDate),
      userId: old.userId,
      patientId: old.patientId,
      serviceType: old.serviceType,
      slotDate: slotDateValue(slotDate),
      slotStart,
      expectedTime: slotInstant(slotDate, slotStart),
      status: 'BOOKED',
      source: old.source,
      createdBy: staff._id,
      rescheduledFromId: old._id,
    });
  } catch (err) {
    await releaseSlot(slotDate, slotStart);
    throw err;
  }
  try {
    await cancelInternal(old, staff, `Đổi giờ sang ${slotStart} ngày ${slotDate.split('-').reverse().join('/')}`);
  } catch (err) {
    await created.deleteOne();
    await releaseSlot(slotDate, slotStart);
    throw err;
  }
  return created;
}

// ---------- Xem phiếu ----------

export function appointmentView(appt: AppointmentDoc, patient: PatientDoc | null | undefined, { forUser = false } = {}) {
  const dateStr = slotDateStr(appt.slotDate);
  const status = forUser ? userStatus(appt.status) : appt.status;
  return {
    id: appt._id.toString(),
    ticketCode: appt.ticketCode,
    serviceType: appt.serviceType,
    serviceLabel: SERVICE_TYPES[appt.serviceType],
    slotDate: dateStr,
    slotStart: appt.slotStart,
    expectedTime: appt.expectedTime,
    status,
    source: appt.source,
    cancelReason: appt.cancelReason,
    cancelledAt: appt.cancelledAt,
    rescheduledFromId: appt.rescheduledFromId ? appt.rescheduledFromId.toString() : null,
    createdAt: appt.createdAt,
    patient: patient
      ? {
          id: patient._id.toString(),
          patientCode: patient.patientCode,
          fullName: patient.fullName,
          relationship: patient.relationship,
          birthYear: patient.birthYear ?? (patient.dob ? patient.dob.getUTCFullYear() : null),
          phone: forUser ? undefined : patient.phone,
          isLocked: patient.isLocked,
        }
      : null,
  };
}

async function withPatients(appts: AppointmentDoc[], opts?: { forUser?: boolean }) {
  const ids = [...new Set(appts.map((a) => a.patientId.toString()))];
  const patients = await Patient.find({ _id: { $in: ids } });
  const map = new Map(patients.map((p) => [p._id.toString(), p]));
  return appts.map((a) => appointmentView(a, map.get(a.patientId.toString()), opts));
}

/** Phiếu khám của mọi hồ sơ thuộc tài khoản (BR.20). */
async function ownPatientIds(userId: Types.ObjectId) {
  const patients = await Patient.find({ userId }).select('_id').lean();
  return patients.map((p) => p._id);
}

export async function listUserAppointments(user: UserDoc, { status }: { status?: string }) {
  const patientIds = await ownPatientIds(user._id);
  const filter: FilterQuery<AppointmentDoc> = { $or: [{ userId: user._id }, { patientId: { $in: patientIds } }] };
  if (status === 'BOOKED') filter.status = { $in: ACTIVE_STATUSES };
  else if (status === 'COMPLETED' || status === 'CANCELLED') filter.status = status;
  const appts = await Appointment.find(filter).sort({ slotDate: -1, slotStart: -1 }).limit(200);
  const items = await withPatients(appts, { forUser: true });
  const recordIds = new Set(
    (await MedicalRecord.find({ appointmentId: { $in: appts.map((a) => a._id) } }).select('appointmentId').lean()).map((r) =>
      r.appointmentId.toString(),
    ),
  );
  return items.map((i) => ({ ...i, hasResult: recordIds.has(i.id) }));
}

export async function getUserAppointment(user: UserDoc, appointmentId: string | Types.ObjectId) {
  const patientIds = await ownPatientIds(user._id);
  const appt = await Appointment.findOne({
    _id: appointmentId,
    $or: [{ userId: user._id }, { patientId: { $in: patientIds } }],
  });
  if (!appt) throw notFound('Không tìm thấy phiếu khám.');
  const patient = await Patient.findById(appt.patientId);
  const hasResult = Boolean(await MedicalRecord.exists({ appointmentId: appt._id }));
  return { ...appointmentView(appt, patient, { forUser: true }), hasResult };
}

/** Lịch trong ngày cho Tiếp tân: lọc theo trạng thái, tra cứu mã phiếu hoặc tên (UC.7.1, UC.7.2). */
export async function listDay({ date, status, q }: { date: string; status?: string; q?: string }) {
  const filter: FilterQuery<AppointmentDoc> = { slotDate: slotDateValue(date) };
  if (isStatus(status)) filter.status = status;
  if (q) {
    const text = q.trim();
    const rx = new RegExp(escapeRegex(text), 'i');
    const patients = await Patient.find({ $or: [{ fullName: rx }, { phone: rx }, { patientCode: rx }] })
      .select('_id')
      .lean();
    filter.$or = [{ ticketCode: rx }, { patientId: { $in: patients.map((p) => p._id) } }];
  }
  const appts = await Appointment.find(filter).sort({ slotStart: 1, createdAt: 1 });
  const [views, checkins, order] = await Promise.all([
    withPatients(appts),
    Checkin.find({ appointmentId: { $in: appts.map((a) => a._id) }, status: { $in: ['WAITING', 'IN_ROOM', 'COMPLETED'] } }).lean(),
    waitingOrder(date),
  ]);
  // Giờ check-in, giờ vào khám và thứ tự trong hàng chờ để tiếp tân thấy ngay trên lịch
  const cMap = new Map(checkins.map((c) => [c.appointmentId.toString(), c]));
  const items = views.map((v) => {
    const c = cMap.get(v.id);
    return {
      ...v,
      checkInTime: c?.checkInTime ?? null,
      enteredAt: c?.enteredAt ?? null,
      priorityGroup: c?.priorityGroup ?? null,
      queueNo: order.get(v.id) ?? null,
    };
  });

  const counts = await Appointment.aggregate<{ _id: AppointmentStatus; n: number }>([
    { $match: { slotDate: slotDateValue(date) } },
    { $group: { _id: '$status', n: { $sum: 1 } } },
  ]);
  const summary = Object.fromEntries(Object.keys(STATUS_LABELS).map((s) => [s, 0]));
  for (const c of counts) summary[c._id] = c.n;
  // Số phiếu đặt hộ tại quầy trong ngày (không tính phiếu đã huỷ)
  const counterCount = await Appointment.countDocuments({ slotDate: slotDateValue(date), source: 'COUNTER', status: { $ne: 'CANCELLED' } });
  return { items, summary, counterCount };
}

/** Tra cứu mã phiếu ở mọi ngày. */
export async function findByTicketCode(code: string) {
  const appt = await Appointment.findOne({ ticketCode: code.trim().toUpperCase() });
  if (!appt) return null;
  return appointmentView(appt, await Patient.findById(appt.patientId));
}

export async function getStaffAppointment(appointmentId: string | Types.ObjectId) {
  const appt = await Appointment.findById(appointmentId);
  if (!appt) throw notFound('Không tìm thấy phiếu khám.');
  return appointmentView(appt, await Patient.findById(appt.patientId));
}

export async function listPatientAppointments(patientId: Types.ObjectId) {
  const appts = await Appointment.find({ patientId }).sort({ slotDate: -1, slotStart: -1 }).limit(200);
  return withPatients(appts);
}
