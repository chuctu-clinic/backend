import { Router } from 'express';
import { z } from 'zod';
import { requireUser } from '../middleware/auth.js';
import { Patient } from '../models/index.js';
import * as appts from '../services/appointment.service.js';
import * as patients from '../services/patient.service.js';
import * as records from '../services/medicalRecord.service.js';
import { ah, badRequest } from '../utils/errors.js';
import { dateStr, optionalText, parse } from '../utils/validate.js';
import { accountUpdateSchema, bookingSchema, profileSchema, profileUpdateSchema } from './schemas.js';

const router = Router();
router.use(requireUser);

// ---------- Thông tin tài khoản (UC.1.4, UC.1.5) ----------

router.get(
  '/',
  ah(async (req, res) => {
    const self = await Patient.findOne({ userId: req.user._id, relationship: 'BAN_THAN' });
    res.json({ user: req.user.toPublic(), patientCode: self?.patientCode ?? null });
  }),
);

router.patch(
  '/',
  ah(async (req, res) => {
    // Số điện thoại là tên đăng nhập nên không tự sửa được (BR.01)
    if (req.body?.phone !== undefined && req.body.phone !== req.user.phone) {
      throw badRequest('Số điện thoại là tên đăng nhập nên không tự sửa được. Cô chú gọi phòng khám nếu cần đổi.');
    }
    const data = parse(accountUpdateSchema, req.body);
    const user = req.user;
    patients.assertFresh(user.infoUpdatedAt ?? user.createdAt, data.expectedUpdatedAt);
    if (data.fullName !== undefined) user.fullName = data.fullName;
    if (data.gender !== undefined) user.gender = data.gender;
    if (data.address !== undefined) user.address = data.address;
    if (data.dob !== undefined) user.dob = data.dob ? new Date(`${data.dob}T00:00:00Z`) : null;
    user.infoUpdatedAt = new Date();
    await user.save();

    const self = await patients.syncSelfProfile(user);
    res.json({ user: user.toPublic(), patientCode: self?.patientCode ?? null });
  }),
);

// ---------- Hồ sơ khám (UC.1.6 – UC.1.8) ----------

router.get(
  '/patients',
  ah(async (req, res) => {
    const list = await patients.listOwnProfiles(req.user._id);
    const last = await patients.lastVisitDates(list.map((p) => p._id));
    res.json({ items: list.map((p) => ({ ...p.toPublic(), lastVisitDate: last[p._id.toString()] ?? null })), max: 10 });
  }),
);

router.post(
  '/patients',
  ah(async (req, res) => {
    const data = parse(profileSchema, req.body);
    if (data.relationship === 'BAN_THAN' && (await Patient.exists({ userId: req.user._id, relationship: 'BAN_THAN' }))) {
      throw badRequest('Tài khoản đã có hồ sơ của chính chủ. Cô chú chọn quan hệ khác.', { fields: { relationship: 'Đã có hồ sơ chính chủ.' } });
    }
    const patient = await patients.createPatient(data, { userId: req.user._id, relationship: data.relationship, createdBy: req.user._id });
    res.status(201).json({ patient: patient.toPublic() });
  }),
);

router.get(
  '/patients/:id',
  ah(async (req, res) => {
    const patient = await patients.getOwnProfile(req.user._id, req.params.id);
    res.json({ patient: patient.toPublic() });
  }),
);

router.patch(
  '/patients/:id',
  ah(async (req, res) => {
    const data = parse(profileUpdateSchema, req.body);
    if (data.relationship === 'BAN_THAN') {
      const other = await Patient.exists({ userId: req.user._id, relationship: 'BAN_THAN', _id: { $ne: req.params.id } });
      if (other) throw badRequest('Tài khoản đã có hồ sơ của chính chủ.', { fields: { relationship: 'Đã có hồ sơ chính chủ.' } });
    }
    const { patient, user } = await patients.updateOwnProfile(req.user._id, req.params.id, data);
    res.json({ patient: patient.toPublic(), user: user?.toPublic() });
  }),
);

router.delete(
  '/patients/:id',
  ah(async (req, res) => {
    res.json(await patients.deleteOwnProfile(req.user._id, req.params.id));
  }),
);

// ---------- Đặt lịch (UC.6.1 – UC.6.3, UC.6.7) ----------

router.get(
  '/availability',
  ah(async (req, res) => {
    const { date } = parse(z.object({ date: dateStr }), req.query);
    res.json({ date, slots: await appts.getAvailability(date, 'USER') });
  }),
);

router.post(
  '/appointments',
  ah(async (req, res) => {
    const data = parse(bookingSchema, req.body);
    const appt = await appts.userBook(req.user, data);
    res.status(201).json({ appointment: await appts.getUserAppointment(req.user, appt._id) });
  }),
);

// ---------- Phiếu khám (UC.6.4 – UC.6.6, UC.6.8, UC.6.9) ----------

router.get(
  '/appointments',
  ah(async (req, res) => {
    res.json({ items: await appts.listUserAppointments(req.user, { status: req.query.status }) });
  }),
);

router.get(
  '/appointments/:id',
  ah(async (req, res) => {
    res.json({ appointment: await appts.getUserAppointment(req.user, req.params.id) });
  }),
);

router.post(
  '/appointments/:id/cancel',
  ah(async (req, res) => {
    const { reason } = parse(z.object({ reason: optionalText(300) }), req.body || {});
    await appts.userCancel(req.user, req.params.id, reason);
    res.json({ appointment: await appts.getUserAppointment(req.user, req.params.id) });
  }),
);

router.get(
  '/appointments/:id/result',
  ah(async (req, res) => {
    res.json({ record: await records.getUserResult(req.user, req.params.id) });
  }),
);

router.get(
  '/history',
  ah(async (req, res) => {
    res.json({ items: await records.listUserHistory(req.user, { patientId: req.query.patientId }) });
  }),
);

export default router;
