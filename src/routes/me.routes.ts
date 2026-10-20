import { Router } from 'express';
import { z } from 'zod';
import { me, requireUser } from '../middleware/auth.ts';
import { Patient } from '../models/index.ts';
import * as appts from '../services/appointment.service.ts';
import * as patients from '../services/patient.service.ts';
import * as records from '../services/medicalRecord.service.ts';
import { ah, badRequest } from '../utils/errors.ts';
import { dateStr, optionalText, parse, str } from '../utils/validate.ts';
import { accountUpdateSchema, bookingSchema, profileSchema, profileUpdateSchema } from './schemas.ts';

const router = Router();
router.use(requireUser);

// ---------- Thông tin tài khoản (UC.1.4, UC.1.5) ----------

router.get(
  '/',
  ah(async (req, res) => {
    const self = await Patient.findOne({ userId: me(req)._id, relationship: 'BAN_THAN' });
    res.json({ user: me(req).toPublic(), patientCode: self?.patientCode ?? null });
  }),
);

router.patch(
  '/',
  ah(async (req, res) => {
    // Số điện thoại là tên đăng nhập nên không tự sửa được (BR.01)
    if (req.body?.phone !== undefined && req.body.phone !== me(req).phone) {
      throw badRequest('Số điện thoại là tên đăng nhập nên không tự sửa được. Cô chú gọi phòng khám nếu cần đổi.');
    }
    const data = parse(accountUpdateSchema, req.body);
    const user = me(req);
    if (data.fullName !== undefined) user.fullName = data.fullName;
    if (data.gender !== undefined) user.gender = data.gender;
    if (data.address !== undefined) user.address = data.address;
    if (data.dob !== undefined) user.dob = data.dob ? new Date(`${data.dob}T00:00:00Z`) : null;
    await user.save();

    // Giữ hồ sơ chính chủ khớp với thông tin tài khoản
    const self = await Patient.findOne({ userId: user._id, relationship: 'BAN_THAN', isLocked: false });
    if (self) {
      self.fullName = user.fullName;
      self.gender = user.gender;
      self.address = user.address;
      self.dob = user.dob;
      if (user.dob) self.birthYear = user.dob.getUTCFullYear();
      await self.save();
    }
    res.json({ user: user.toPublic(), patientCode: self?.patientCode ?? null });
  }),
);

// ---------- Hồ sơ khám (UC.1.6 – UC.1.8) ----------

router.get(
  '/patients',
  ah(async (req, res) => {
    const list = await patients.listOwnProfiles(me(req)._id);
    res.json({ items: list.map((p) => p.toPublic()), max: 10 });
  }),
);

router.post(
  '/patients',
  ah(async (req, res) => {
    const data = parse(profileSchema, req.body);
    if (data.relationship === 'BAN_THAN' && (await Patient.exists({ userId: me(req)._id, relationship: 'BAN_THAN' }))) {
      throw badRequest('Tài khoản đã có hồ sơ của chính chủ. Cô chú chọn quan hệ khác.', { fields: { relationship: 'Đã có hồ sơ chính chủ.' } });
    }
    const patient = await patients.createPatient(data, { userId: me(req)._id, relationship: data.relationship, createdBy: me(req)._id });
    res.status(201).json({ patient: patient.toPublic() });
  }),
);

router.get(
  '/patients/:id',
  ah(async (req, res) => {
    const patient = await patients.getOwnProfile(me(req)._id, req.params.id);
    res.json({ patient: patient.toPublic() });
  }),
);

router.patch(
  '/patients/:id',
  ah(async (req, res) => {
    const data = parse(profileUpdateSchema, req.body);
    if (data.relationship === 'BAN_THAN') {
      const other = await Patient.exists({ userId: me(req)._id, relationship: 'BAN_THAN', _id: { $ne: req.params.id } });
      if (other) throw badRequest('Tài khoản đã có hồ sơ của chính chủ.', { fields: { relationship: 'Đã có hồ sơ chính chủ.' } });
    }
    const patient = await patients.updateOwnProfile(me(req)._id, req.params.id, data);
    res.json({ patient: patient.toPublic() });
  }),
);

router.delete(
  '/patients/:id',
  ah(async (req, res) => {
    res.json(await patients.deleteOwnProfile(me(req)._id, req.params.id));
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
    const appt = await appts.userBook(me(req), data);
    res.status(201).json({ appointment: await appts.getUserAppointment(me(req), appt._id) });
  }),
);

// ---------- Phiếu khám (UC.6.4 – UC.6.6, UC.6.8, UC.6.9) ----------

router.get(
  '/appointments',
  ah(async (req, res) => {
    res.json({ items: await appts.listUserAppointments(me(req), { status: str(req.query.status) }) });
  }),
);

router.get(
  '/appointments/:id',
  ah(async (req, res) => {
    res.json({ appointment: await appts.getUserAppointment(me(req), req.params.id) });
  }),
);

router.post(
  '/appointments/:id/cancel',
  ah(async (req, res) => {
    const { reason } = parse(z.object({ reason: optionalText(300) }), req.body || {});
    await appts.userCancel(me(req), req.params.id, reason);
    res.json({ appointment: await appts.getUserAppointment(me(req), req.params.id) });
  }),
);

router.get(
  '/appointments/:id/result',
  ah(async (req, res) => {
    res.json({ record: await records.getUserResult(me(req), req.params.id) });
  }),
);

router.get(
  '/history',
  ah(async (req, res) => {
    res.json({ items: await records.listUserHistory(me(req), { patientId: str(req.query.patientId) }) });
  }),
);

export default router;
