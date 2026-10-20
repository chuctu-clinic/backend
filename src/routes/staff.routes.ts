import { Router } from 'express';
import { z } from 'zod';
import { me, requireStaff } from '../middleware/auth.ts';
import * as appts from '../services/appointment.service.ts';
import * as patients from '../services/patient.service.ts';
import * as queue from '../services/queue.service.ts';
import * as records from '../services/medicalRecord.service.ts';
import { ah } from '../utils/errors.ts';
import { dateStr, objectId, paging, parse, str } from '../utils/validate.ts';
import { todayVN } from '../utils/time.ts';
import { recordSchema, serviceType, slotStart, staffPatientSchema, staffPatientUpdateSchema } from './schemas.ts';

// Tiếp tân và Admin (BR.07, BR.23, BR.34). Admin có toàn quyền nên dùng chung các API này.
const router = Router();
router.use(requireStaff);

// ---------- Lịch trong ngày (UC.7.1, UC.7.2) ----------

router.get(
  '/appointments',
  ah(async (req, res) => {
    const date = str(req.query.date) || todayVN();
    parse(z.object({ date: dateStr }), { date });
    res.json({ date, ...(await appts.listDay({ date, status: str(req.query.status), q: str(req.query.q) })) });
  }),
);

router.get(
  '/appointments/lookup',
  ah(async (req, res) => {
    const { code } = parse(z.object({ code: z.string().trim().min(3, 'Vui lòng nhập mã phiếu khám.') }), req.query);
    res.json({ appointment: await appts.findByTicketCode(code) });
  }),
);

router.get(
  '/appointments/:id',
  ah(async (req, res) => {
    const appointment = await appts.getStaffAppointment(req.params.id);
    res.json({ appointment, record: await records.getRecordByAppointment(req.params.id) });
  }),
);

router.get(
  '/availability',
  ah(async (req, res) => {
    const { date } = parse(z.object({ date: dateStr }), req.query);
    res.json({ date, slots: await appts.getAvailability(date, 'COUNTER') });
  }),
);

// ---------- Đặt hộ, đổi giờ, huỷ (UC.7.5 – UC.7.7) ----------

router.post(
  '/appointments',
  ah(async (req, res) => {
    const data = parse(
      z.object({ patientId: objectId, serviceType, slotDate: dateStr, slotStart, checkInNow: z.boolean().optional() }),
      req.body,
    );
    const appt = await appts.counterBook(me(req), data);
    if (data.checkInNow) await queue.checkIn(me(req), appt._id);
    res.status(201).json({ appointment: await appts.getStaffAppointment(appt._id) });
  }),
);

router.post(
  '/appointments/:id/reschedule',
  ah(async (req, res) => {
    const data = parse(z.object({ slotDate: dateStr, slotStart }), req.body);
    const appt = await appts.reschedule(me(req), req.params.id, data);
    res.json({ appointment: await appts.getStaffAppointment(appt._id) });
  }),
);

router.post(
  '/appointments/:id/cancel',
  ah(async (req, res) => {
    const { reason } = parse(
      z.object({ reason: z.string({ required_error: 'Vui lòng chọn lý do huỷ.' }).trim().min(1, 'Vui lòng chọn lý do huỷ.').max(300) }),
      req.body,
    );
    await appts.counterCancel(me(req), req.params.id, reason);
    res.json({ appointment: await appts.getStaffAppointment(req.params.id) });
  }),
);

// ---------- Check-in (UC.7.3, UC.7.4) ----------

router.post(
  '/appointments/:id/checkin',
  ah(async (req, res) => {
    await queue.checkIn(me(req), req.params.id);
    res.json({ appointment: await appts.getStaffAppointment(req.params.id) });
  }),
);

router.post(
  '/appointments/:id/undo-checkin',
  ah(async (req, res) => {
    await queue.undoCheckIn(req.params.id);
    res.json({ appointment: await appts.getStaffAppointment(req.params.id) });
  }),
);

// ---------- Hàng đợi (UC.10.1 – UC.10.5) ----------

router.get(
  '/queue',
  ah(async (req, res) => {
    res.json(await queue.queueState(todayVN()));
  }),
);

router.get(
  '/queue/mini',
  ah(async (req, res) => {
    res.json(await queue.miniQueue(todayVN()));
  }),
);

router.post(
  '/queue/invite',
  ah(async (req, res) => {
    await queue.inviteNext(todayVN());
    res.json(await queue.queueState(todayVN()));
  }),
);

router.post(
  '/queue/:checkinId/undo-enter',
  ah(async (req, res) => {
    await queue.undoEnter(req.params.checkinId);
    res.json(await queue.queueState(todayVN()));
  }),
);

router.put(
  '/queue/auto-invite',
  ah(async (req, res) => {
    const { enabled } = parse(z.object({ enabled: z.boolean() }), req.body);
    await queue.setAutoInvite(enabled);
    if (enabled) await queue.fillSeats(todayVN());
    res.json(await queue.queueState(todayVN()));
  }),
);

// ---------- Hồ sơ bệnh nhân (UC.9.1 – UC.9.5, UC.9.8) ----------

router.get(
  '/patients',
  ah(async (req, res) => {
    const { page, limit, skip } = paging(req.query);
    const { items, total } = await patients.searchPatients({ q: str(req.query.q), locked: str(req.query.locked), skip, limit });
    res.json({ items, total, page, limit });
  }),
);

router.get(
  '/patients/duplicates',
  ah(async (req, res) => {
    const list = await patients.findDuplicatesByPhone(str(req.query.phone), str(req.query.excludeId));
    res.json({ items: list.map((p) => p.toPublic()) });
  }),
);

router.post(
  '/patients',
  ah(async (req, res) => {
    const data = parse(staffPatientSchema, req.body);
    const patient = await patients.staffCreatePatient(data, me(req), { confirmDuplicate: req.body.confirmDuplicate === true });
    res.status(201).json({ patient: patient.toPublic() });
  }),
);

router.get(
  '/patients/:id',
  ah(async (req, res) => {
    const patient = await patients.getPatient(req.params.id);
    const [appointments, history] = await Promise.all([
      appts.listPatientAppointments(patient._id),
      records.listPatientRecords(patient._id),
    ]);
    res.json({ patient: patient.toPublic(), appointments, records: history });
  }),
);

router.patch(
  '/patients/:id',
  ah(async (req, res) => {
    const data = parse(staffPatientUpdateSchema, req.body);
    const patient = await patients.staffUpdatePatient(req.params.id, data);
    res.json({ patient: patient.toPublic() });
  }),
);

// ---------- Phiếu kết quả khám (UC.9.7, UC.9.9) ----------

router.post(
  '/appointments/:id/record',
  ah(async (req, res) => {
    const data = parse(recordSchema, req.body);
    res.status(201).json({ record: await records.createRecord(me(req), req.params.id, data) });
  }),
);

router.get(
  '/records/:id',
  ah(async (req, res) => {
    res.json({ record: await records.getRecord(req.params.id) });
  }),
);

router.put(
  '/records/:id',
  ah(async (req, res) => {
    const data = parse(recordSchema, req.body);
    res.json({ record: await records.updateRecord(me(req), req.params.id, data) });
  }),
);

export default router;
