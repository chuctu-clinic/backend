import { rules } from '../config.js';
import { Appointment, Checkin, Patient, getSetting, setSetting } from '../models/index.js';
import { RECEIVING_HOURS } from '../shared/constants.js';
import { conflict, forbidden, notFound } from '../utils/errors.js';
import { clock, hhmmToMinutes, minutesOfDayVN, slotDateStr, slotDateValue, slotInstant, toDateStr, todayVN } from '../utils/time.js';

const AUTO_KEY = 'queue.autoInvite';
const { seats } = rules.queue;

export const getAutoInvite = () => getSetting(AUTO_KEY, true);
export const setAutoInvite = (value) => setSetting(AUTO_KEY, Boolean(value));

/**
 * Nhóm ưu tiên: phiếu đặt trước và đến đúng khung giờ. Khách đến trực tiếp (đặt hộ ngay trong ngày)
 * hoặc đến sau khi khung giờ kết thúc xếp nhóm thường (BR.28).
 */
export function priorityGroupFor(appt, checkInTime) {
  const dateStr = slotDateStr(appt.slotDate);
  const walkIn = appt.source === 'COUNTER' && toDateStr(appt.createdAt) === dateStr;
  if (walkIn) return 'NORMAL';
  const start = slotInstant(dateStr, appt.slotStart).getTime();
  const windowStart = start - rules.queue.priorityEarlyMinutes * 60_000;
  const windowEnd = start + rules.booking.slotMinutes * 60_000;
  const t = checkInTime.getTime();
  return t >= windowStart && t < windowEnd ? 'PRIORITY' : 'NORMAL';
}

// ---------- Check-in ----------

/** Đang trong giờ nhận bệnh (giờ Việt Nam): từ giờ mở cửa đến trước giờ đóng cửa. */
export function isReceivingTime(now = clock.now()) {
  const m = minutesOfDayVN(now);
  return m >= hhmmToMinutes(RECEIVING_HOURS.open) && m < hhmmToMinutes(RECEIVING_HOURS.close);
}

export function assertReceivingTime(now = clock.now()) {
  if (!isReceivingTime(now)) {
    throw conflict(`Ngoài giờ nhận bệnh (${RECEIVING_HOURS.label}) nên không check-in được.`, 'CLOSED');
  }
}

export async function checkIn(staff, appointmentId, now = clock.now()) {
  const appt = await Appointment.findById(appointmentId);
  if (!appt) throw notFound('Không tìm thấy phiếu khám.');
  if (appt.status === 'CANCELLED') throw conflict('Phiếu khám đã huỷ nên không check-in được.', 'CANCELLED');
  if (appt.status !== 'BOOKED') throw conflict('Phiếu khám này đã được check-in.', 'ALREADY_CHECKED_IN');
  const today = todayVN(now);
  if (slotDateStr(appt.slotDate) !== today) {
    throw conflict('Phiếu khám không thuộc ngày hôm nay. Đổi giờ khám sang hôm nay trước khi check-in.', 'WRONG_DAY');
  }
  assertReceivingTime(now);
  const patient = await Patient.findById(appt.patientId);
  if (patient?.isLocked) throw forbidden('Hồ sơ đang bị khoá nên không ghi nhận lượt khám mới.');

  const updated = await Appointment.findOneAndUpdate(
    { _id: appt._id, status: 'BOOKED' },
    { $set: { status: 'CHECKED_IN' } },
    { new: true },
  );
  if (!updated) throw conflict('Phiếu khám vừa thay đổi trạng thái, vui lòng tải lại.', 'STALE');

  try {
    await Checkin.create({
      appointmentId: appt._id,
      patientId: appt.patientId,
      visitDate: today,
      checkInTime: now,
      priorityGroup: priorityGroupFor(appt, now),
      status: 'WAITING',
    });
  } catch (err) {
    await Appointment.updateOne({ _id: appt._id, status: 'CHECKED_IN' }, { $set: { status: 'BOOKED' } });
    throw err;
  }
  if (await getAutoInvite()) await fillSeats(today);
  return updated;
}

/** Hoàn tác check-in khi nhầm: phiếu về Chưa đến, xoá thời điểm tiếp nhận (BR.27). */
export async function undoCheckIn(appointmentId) {
  const checkin = await Checkin.findOne({ appointmentId, status: { $in: ['WAITING', 'IN_ROOM'] } });
  if (!checkin) throw conflict('Phiếu khám này chưa được check-in.', 'NOT_CHECKED_IN');
  if (checkin.status === 'IN_ROOM') throw conflict('Bệnh nhân đã vào khám. Hoàn tác vào khám trước.', 'IN_ROOM');
  const reverted = await Checkin.findOneAndUpdate(
    { _id: checkin._id, status: 'WAITING' },
    { $set: { status: 'REVERTED' } },
    { new: true },
  );
  if (!reverted) throw conflict('Hàng đợi vừa thay đổi, vui lòng tải lại.', 'STALE');
  await Appointment.updateOne({ _id: appointmentId, status: 'CHECKED_IN' }, { $set: { status: 'BOOKED' } });
  return reverted;
}

// ---------- Hàng đợi ----------

function sortWaiting(list) {
  return list.sort((a, b) => {
    if (a.priorityGroup !== b.priorityGroup) return a.priorityGroup === 'PRIORITY' ? -1 : 1;
    return a.checkInTime - b.checkInTime;
  });
}

async function waitingList(date) {
  return sortWaiting(await Checkin.find({ visitDate: date, status: 'WAITING' }));
}

/** Mời một người vào ghế trống thấp nhất. Trả về check-in đã mời hoặc null nếu hết ghế. */
async function seatOne(checkin, date, now) {
  for (let attempt = 0; attempt < seats; attempt += 1) {
    const taken = new Set((await Checkin.find({ visitDate: date, status: 'IN_ROOM' }).select('seatNo').lean()).map((c) => c.seatNo));
    const seatNo = [...Array(seats).keys()].map((i) => i + 1).find((n) => !taken.has(n));
    if (!seatNo) return null;
    try {
      const seated = await Checkin.findOneAndUpdate(
        { _id: checkin._id, status: 'WAITING' },
        { $set: { status: 'IN_ROOM', seatNo, enteredAt: now } },
        { new: true },
      );
      if (!seated) return null;
      await Appointment.updateOne({ _id: seated.appointmentId, status: 'CHECKED_IN' }, { $set: { status: 'IN_PROGRESS' } });
      return seated;
    } catch (err) {
      // Ghế vừa bị người khác nhận (khoá duy nhất visitDate + seatNo): thử ghế kế tiếp
      if (err?.code !== 11000) throw err;
    }
  }
  return null;
}

/** Mời người đầu hàng chờ vào khám (UC.10.2). */
export async function inviteNext(date = todayVN(), now = new Date()) {
  const [head] = await waitingList(date);
  if (!head) throw conflict('Chưa có ai đang chờ.', 'QUEUE_EMPTY');
  const seated = await seatOne(head, date, now);
  if (!seated) throw conflict(`Phòng khám đã đủ ${seats} người.`, 'ROOM_FULL');
  return seated;
}

/** Mời đúng một người đã check-in vào khám, bấm từ Lịch trong ngày. */
export async function enterRoom(appointmentId, now = new Date()) {
  const checkin = await Checkin.findOne({ appointmentId, status: 'WAITING' });
  if (!checkin) throw conflict('Người này không còn trong hàng chờ.', 'NOT_WAITING');
  const seated = await seatOne(checkin, checkin.visitDate, now);
  if (!seated) throw conflict(`Phòng khám đã đủ ${seats} người.`, 'ROOM_FULL');
  return seated;
}

/** Hoàn tác vào khám theo phiếu khám, bấm từ Lịch trong ngày. */
export async function undoEnterByAppointment(appointmentId) {
  const checkin = await Checkin.findOne({ appointmentId, status: 'IN_ROOM' });
  if (!checkin) throw conflict('Người này không còn trong phòng khám.', 'NOT_IN_ROOM');
  return undoEnter(checkin._id);
}

/** Tự động lấp ghế trống bằng người đầu hàng chờ (BR.29). */
export async function fillSeats(date = todayVN(), { exclude = [] } = {}, now = new Date()) {
  const skip = new Set(exclude.map(String));
  const waiting = (await waitingList(date)).filter((c) => !skip.has(c._id.toString()));
  for (const c of waiting) {
    const seated = await seatOne(c, date, now);
    if (!seated) break;
  }
}

/** Hoàn tác vào khám: trở lại hàng chờ đúng vị trí theo giờ check-in (BR.27). */
export async function undoEnter(checkinId) {
  const checkin = await Checkin.findOneAndUpdate(
    { _id: checkinId, status: 'IN_ROOM' },
    { $set: { status: 'WAITING', seatNo: null, enteredAt: null } },
    { new: true },
  );
  if (!checkin) throw conflict('Người này không còn trong phòng khám.', 'NOT_IN_ROOM');
  await Appointment.updateOne({ _id: checkin.appointmentId, status: 'IN_PROGRESS' }, { $set: { status: 'CHECKED_IN' } });
  // Ghế vừa trống nhận người kế tiếp; người vừa hoàn tác vào ở ghế trống sau
  if (await getAutoInvite()) await fillSeats(checkin.visitDate, { exclude: [checkin._id] });
  return checkin;
}

/** Sau khi lưu phiếu kết quả: rời hàng đợi và mời người kế tiếp (BR.30). */
export async function completeCheckin(checkinId, now = new Date()) {
  const checkin = await Checkin.findOneAndUpdate(
    { _id: checkinId, status: 'IN_ROOM' },
    { $set: { status: 'COMPLETED', completedAt: now, seatNo: null } },
    { new: true },
  );
  if (checkin && (await getAutoInvite())) await fillSeats(checkin.visitDate);
  return checkin;
}

// ---------- Xem hàng đợi ----------

async function patientsMap(ids) {
  const patients = await Patient.find({ _id: { $in: ids } }).lean();
  return new Map(patients.map((p) => [p._id.toString(), p]));
}

function checkinView(c, appt, patient, now) {
  return {
    id: c._id.toString(),
    appointmentId: c.appointmentId.toString(),
    ticketCode: appt?.ticketCode,
    slotStart: appt?.slotStart,
    serviceType: appt?.serviceType,
    source: appt?.source,
    patient: patient ? { id: patient._id.toString(), fullName: patient.fullName, patientCode: patient.patientCode, birthYear: patient.birthYear } : null,
    priorityGroup: c.priorityGroup,
    status: c.status,
    seatNo: c.seatNo,
    checkInTime: c.checkInTime,
    enteredAt: c.enteredAt,
    waitedMinutes: Math.max(0, Math.round((now - c.checkInTime) / 60_000)),
  };
}

export async function queueState(date = todayVN(), now = new Date()) {
  const checkins = await Checkin.find({ visitDate: date, status: { $in: ['WAITING', 'IN_ROOM', 'COMPLETED'] } });
  const appts = await Appointment.find({ _id: { $in: checkins.map((c) => c.appointmentId) } }).lean();
  const apptMap = new Map(appts.map((a) => [a._id.toString(), a]));
  const pMap = await patientsMap(checkins.map((c) => c.patientId));
  const view = (c) => checkinView(c, apptMap.get(c.appointmentId.toString()), pMap.get(c.patientId.toString()), now);

  const inRoom = checkins.filter((c) => c.status === 'IN_ROOM').sort((a, b) => a.seatNo - b.seatNo).map(view);
  const waiting = sortWaiting(checkins.filter((c) => c.status === 'WAITING')).map(view);
  const seatList = [...Array(seats).keys()].map((i) => inRoom.find((c) => c.seatNo === i + 1) || { seatNo: i + 1, empty: true });

  return {
    date,
    capacity: seats,
    autoInvite: await getAutoInvite(),
    seats: seatList,
    inRoomCount: inRoom.length,
    waiting,
    waitingCount: waiting.length,
    completedCount: checkins.filter((c) => c.status === 'COMPLETED').length,
    slotMap: await slotMap(date, checkins, apptMap, pMap),
  };
}

/** Sơ đồ ghế theo khung giờ: mỗi ô là một người đã check-in vào khung đó (UC.10.5). */
async function slotMap(date, checkins, apptMap, pMap) {
  const booked = await Appointment.aggregate([
    { $match: { slotDate: slotDateValue(date), status: { $ne: 'CANCELLED' } } },
    { $group: { _id: '$slotStart', total: { $sum: 1 }, notArrived: { $sum: { $cond: [{ $eq: ['$status', 'BOOKED'] }, 1, 0] } } } },
  ]);
  const bookedMap = new Map(booked.map((b) => [b._id, b]));
  return rules.booking.slots.map((start) => {
    const people = checkins
      .filter((c) => apptMap.get(c.appointmentId.toString())?.slotStart === start)
      .sort((a, b) => a.checkInTime - b.checkInTime)
      .map((c) => ({
        id: c._id.toString(),
        fullName: pMap.get(c.patientId.toString())?.fullName,
        ticketCode: apptMap.get(c.appointmentId.toString())?.ticketCode,
        status: c.status,
        seatNo: c.seatNo,
      }));
    const b = bookedMap.get(start);
    return {
      start,
      startMinutes: hhmmToMinutes(start),
      booked: b?.total || 0,
      capacity: rules.booking.slotCapacity,
      notArrived: b?.notArrived || 0,
      checkins: people,
    };
  });
}

/**
 * Thông tin check-in của từng phiếu trong ngày, gắn vào Lịch trong ngày: giờ check-in, giờ vào khám,
 * nhóm ưu tiên và thứ tự trong hàng chờ.
 */
export async function checkinsByAppointment(appointmentIds) {
  const checkins = await Checkin.find({ appointmentId: { $in: appointmentIds }, status: { $in: ['WAITING', 'IN_ROOM', 'COMPLETED'] } });
  const order = new Map();
  const byDate = new Map();
  for (const c of checkins.filter((x) => x.status === 'WAITING')) {
    if (!byDate.has(c.visitDate)) byDate.set(c.visitDate, []);
    byDate.get(c.visitDate).push(c);
  }
  for (const list of byDate.values()) sortWaiting(list).forEach((c, i) => order.set(c._id.toString(), i + 1));
  return new Map(
    checkins.map((c) => [
      c.appointmentId.toString(),
      {
        id: c._id.toString(),
        status: c.status,
        priorityGroup: c.priorityGroup,
        checkInTime: c.checkInTime,
        enteredAt: c.enteredAt,
        queueNo: order.get(c._id.toString()) ?? null,
      },
    ]),
  );
}
