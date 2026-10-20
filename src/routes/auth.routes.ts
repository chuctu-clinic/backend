import { Router, type Request, type Response } from 'express';
import { z } from 'zod';
import { rules } from '../config.ts';
import { DEVICE_COOKIE, clearSession, cookie, me, requireAuth, setDeviceCookie, setSession } from '../middleware/auth.ts';
import type { UserDoc } from '../models/index.ts';
import * as auth from '../services/auth.service.ts';
import { ah, badRequest } from '../utils/errors.ts';
import { dateStr, fullName, gender, optionalText, parse, phone } from '../utils/validate.ts';
import { todayVN } from '../utils/time.ts';

const router = Router();

const registerSchema = z.object({
  fullName,
  phone,
  dob: dateStr.refine((s) => s < todayVN(), 'Ngày sinh phải trước hôm nay.'),
  gender,
  address: optionalText(300),
});
const codeSchema = z.object({
  phone,
  code: z.string({ required_error: 'Cô chú vui lòng nhập mã xác nhận.' }).regex(/^\d{6}$/, 'Mã xác nhận gồm 6 chữ số.'),
  remember: z.boolean().optional().default(true),
});

/** Ghi nhận thiết bị đã xác nhận để lần sau không phải nhập mã (BR.02). */
async function rememberDevice(req: Request, res: Response, user: UserDoc) {
  let deviceId = cookie(req, DEVICE_COOKIE);
  if (!deviceId) {
    deviceId = auth.newDeviceId();
    setDeviceCookie(res, deviceId);
  }
  await auth.trustDevice(user._id, deviceId);
}

router.get('/me', (req, res) => {
  res.json({ user: req.user ? req.user.toPublic() : null });
});

// ---------- Người dùng ----------

router.post(
  '/register',
  ah(async (req, res) => {
    const data = parse(registerSchema, req.body);
    res.json(await auth.startRegister(data));
  }),
);

router.post(
  '/register/verify',
  ah(async (req, res) => {
    const { phone: p, code } = parse(codeSchema, req.body);
    const user = await auth.completeRegister(p, code);
    await rememberDevice(req, res, user);
    setSession(res, user);
    res.status(201).json({ user: user.toPublic() });
  }),
);

router.post(
  '/login',
  ah(async (req, res) => {
    const { phone: p } = parse(z.object({ phone }), req.body);
    const result = await auth.startLogin(p, cookie(req, DEVICE_COOKIE));
    if (!result.needCode) {
      setSession(res, result.user);
      return res.json({ needCode: false, user: result.user.toPublic() });
    }
    res.json({ needCode: true, ...result.otp });
  }),
);

router.post(
  '/login/verify',
  ah(async (req, res) => {
    const { phone: p, code, remember } = parse(codeSchema, req.body);
    const user = await auth.completeLogin(p, code);
    if (remember) await rememberDevice(req, res, user);
    setSession(res, user, { remember });
    res.json({ user: user.toPublic() });
  }),
);

router.post(
  '/otp/resend',
  ah(async (req, res) => {
    const { phone: p, purpose } = parse(z.object({ phone, purpose: z.enum(['REGISTER', 'NEW_DEVICE']) }), req.body);
    res.json(await auth.resendCode(p, purpose));
  }),
);

// ---------- Nội bộ ----------

router.post(
  '/staff/login',
  ah(async (req, res) => {
    const { identifier, password } = parse(
      z.object({
        identifier: z.string().trim().min(1, 'Vui lòng nhập số điện thoại hoặc email.'),
        password: z.string().min(1, 'Vui lòng nhập mật khẩu.'),
      }),
      req.body,
    );
    const user = await auth.staffLogin(identifier, password);
    setSession(res, user);
    res.json({ user: user.toPublic() });
  }),
);

router.post(
  '/change-password',
  requireAuth,
  ah(async (req, res) => {
    const { currentPassword, newPassword } = parse(
      z.object({
        currentPassword: z.string().min(1, 'Vui lòng nhập mật khẩu hiện tại.'),
        newPassword: z.string().min(rules.minPasswordLength, `Mật khẩu mới cần tối thiểu ${rules.minPasswordLength} ký tự.`),
      }),
      req.body,
    );
    const current = me(req);
    if (current.role === 'USER') throw badRequest('Tài khoản người dùng không dùng mật khẩu.');
    const user = await auth.changePassword(current._id, currentPassword, newPassword);
    res.json({ user: user.toPublic() });
  }),
);

router.post('/logout', (req, res) => {
  clearSession(res);
  res.json({ ok: true });
});

export default router;
