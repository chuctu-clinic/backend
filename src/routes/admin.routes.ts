import { Router } from 'express';
import { z } from 'zod';
import { rules } from '../config.ts';
import { me, requireAdmin } from '../middleware/auth.ts';
import { POST_CATEGORY_KEYS, ROLES } from '../models/index.ts';
import * as accounts from '../services/account.service.ts';
import * as dashboard from '../services/dashboard.service.ts';
import * as patients from '../services/patient.service.ts';
import * as posts from '../services/post.service.ts';
import { ah } from '../utils/errors.ts';
import { fullName, optionalText, paging, parse, phone, str } from '../utils/validate.ts';

// Chức năng chỉ dành cho Admin (BR.05, BR.10, BR.31)
const router = Router();
router.use(requireAdmin);

router.get(
  '/dashboard',
  ah(async (req, res) => {
    res.json(await dashboard.overview());
  }),
);

// ---------- Quản lý tài khoản (UC.8.1 – UC.8.5) ----------

const email = z
  .union([z.string().trim().toLowerCase().email('Email chưa đúng định dạng.'), z.literal(''), z.null()])
  .optional()
  .transform((v) => (v ? v : null));
const role = z.enum(ROLES, { errorMap: () => ({ message: 'Vui lòng chọn vai trò.' }) });
const tempPassword = z.string().min(rules.minPasswordLength, `Mật khẩu tạm cần tối thiểu ${rules.minPasswordLength} ký tự.`);

router.get(
  '/accounts',
  ah(async (req, res) => {
    const { page, limit, skip } = paging(req.query);
    const result = await accounts.listAccounts({ q: str(req.query.q), role: str(req.query.role), status: str(req.query.status), skip, limit });
    res.json({ ...result, page, limit });
  }),
);

router.post(
  '/accounts',
  ah(async (req, res) => {
    const data = parse(z.object({ fullName, phone, email, role, tempPassword: tempPassword.optional() }), req.body);
    const user = await accounts.createAccount(data);
    res.status(201).json({ account: user.toPublic() });
  }),
);

router.get(
  '/accounts/:id',
  ah(async (req, res) => {
    res.json({ account: (await accounts.getAccount(req.params.id)).toPublic() });
  }),
);

router.patch(
  '/accounts/:id',
  ah(async (req, res) => {
    const data = parse(
      z.object({ fullName: fullName.optional(), phone: phone.optional(), email, address: optionalText(300) }),
      req.body,
    );
    res.json({ account: (await accounts.updateAccount(req.params.id, data)).toPublic() });
  }),
);

router.post(
  '/accounts/:id/lock',
  ah(async (req, res) => {
    res.json({ account: (await accounts.setAccountActive(me(req), req.params.id, false)).toPublic() });
  }),
);

router.post(
  '/accounts/:id/unlock',
  ah(async (req, res) => {
    res.json({ account: (await accounts.setAccountActive(me(req), req.params.id, true)).toPublic() });
  }),
);

router.put(
  '/accounts/:id/role',
  ah(async (req, res) => {
    const data = parse(z.object({ role, tempPassword: tempPassword.optional() }), req.body);
    res.json({ account: (await accounts.setRole(me(req), req.params.id, data.role, data.tempPassword)).toPublic() });
  }),
);

router.post(
  '/accounts/:id/reset-password',
  ah(async (req, res) => {
    const data = parse(z.object({ tempPassword }), req.body);
    res.json({ account: (await accounts.resetPassword(req.params.id, data.tempPassword)).toPublic() });
  }),
);

// ---------- Khoá hồ sơ bệnh nhân (UC.9.6) ----------

router.post(
  '/patients/:id/lock',
  ah(async (req, res) => {
    res.json({ patient: (await patients.setPatientLock(req.params.id, true)).toPublic() });
  }),
);

router.post(
  '/patients/:id/unlock',
  ah(async (req, res) => {
    res.json({ patient: (await patients.setPatientLock(req.params.id, false)).toPublic() });
  }),
);

// ---------- Quản lý bài viết (UC.5.1 – UC.5.5) ----------

const postSchema = z.object({
  title: z.string().trim().max(200, 'Tiêu đề tối đa 200 ký tự.').default(''),
  category: z.enum(POST_CATEGORY_KEYS, { errorMap: () => ({ message: 'Vui lòng chọn chuyên mục.' }) }),
  summary: z.string().trim().max(400, 'Tóm tắt tối đa 400 ký tự.').optional().default(''),
  thumbnailUrl: z
    .union([z.string().trim().url('Đường dẫn ảnh chưa đúng.'), z.literal(''), z.null()])
    .optional()
    .transform((v) => (v ? v : null)),
  authorName: z.string().trim().max(100).optional(),
  publishedAt: z.string().datetime({ offset: true }).optional().nullable(),
  content: z.string().max(100_000).optional().default(''),
  status: z.enum(['DRAFT', 'PUBLISHED']).default('DRAFT'),
});

router.get(
  '/posts',
  ah(async (req, res) => {
    const { page, limit, skip } = paging(req.query);
    const result = await posts.adminList({ status: str(req.query.status), category: str(req.query.category), q: str(req.query.q), skip, limit });
    res.json({ ...result, page, limit });
  }),
);

router.post(
  '/posts',
  ah(async (req, res) => {
    const data = parse(postSchema, req.body);
    if (!data.title) parse(z.object({ title: z.string().min(1, 'Bài viết cần có tiêu đề.') }), data);
    res.status(201).json({ post: await posts.createPost(me(req), data) });
  }),
);

router.get(
  '/posts/:id',
  ah(async (req, res) => {
    res.json({ post: await posts.adminGet(req.params.id) });
  }),
);

router.put(
  '/posts/:id',
  ah(async (req, res) => {
    const data = parse(postSchema.partial(), req.body);
    res.json({ post: await posts.updatePost(req.params.id, data) });
  }),
);

router.delete(
  '/posts/:id',
  ah(async (req, res) => {
    res.json(await posts.deletePost(req.params.id));
  }),
);

export default router;
