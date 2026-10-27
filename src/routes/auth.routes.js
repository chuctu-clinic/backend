import { Router } from 'express';
import { z } from 'zod';
import { rules } from '../config.js';
import { DEVICE_COOKIE, clearSession, requireAuth, setDeviceCookie, setSession } from '../middleware/auth.js';
import * as auth from '../services/auth.service.js';
import { ah, badRequest } from '../utils/errors.js';
import { dateStr, fullName, gender, optionalText, parse, phone } from '../utils/validate.js';
import { todayVN } from '../utils/time.js';

const router = Router();

const registerSchema = z.object({
  fullName,
  phone,
  dob: dateStr.refine((s) => s < todayVN(), 'Ngày sinh phải trước hôm nay.'),
  gender,
  address: optionalText(300),
});
// Xác nhận số điện thoại bằng mã 6 số (server tự gửi) hoặc ID token Firebase (SMS_PROVIDER=firebase)
const codeSchema = z.object({
  phone,
  code: z.string().regex(/^\d{6}$/, 'Mã xác nhận gồm 6 chữ số.').optional(),
  idToken: z.string().min(20).max(4096).optional(),
  remember: z.boolean().optional().default(true),
});

/** Ghi nhận thiết bị đã xác nhận để lần sau không phải nhập mã (BR.02). */
async function rememberDevice(req, res, user) {
  let deviceId = req.cookies?.[DEVICE_COOKIE];
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
    const { phone: p, code, idToken } = parse(codeSchema, req.body);
    const user = await auth.completeRegister(p, { code, idToken });
    await rememberDevice(req, res, user);
    setSession(res, user);
    res.status(201).json({ user: user.toPublic() });
  }),
);

router.post(
  '/login',
  ah(async (req, res) => {
    const { phone: p } = parse(z.object({ phone }), req.body);
    const result = await auth.startLogin(p, req.cookies?.[DEVICE_COOKIE]);
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
    const { phone: p, code, idToken, remember } = parse(codeSchema, req.body);
    const user = await auth.completeLogin(p, { code, idToken });
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
    if (req.user.role === 'USER') throw badRequest('Tài khoản người dùng không dùng mật khẩu.');
    const user = await auth.changePassword(req.user._id, currentPassword, newPassword);
    res.json({ user: user.toPublic() });
  }),
);

router.post('/logout', (req, res) => {
  clearSession(res);
  res.json({ ok: true });
});

export default router;
