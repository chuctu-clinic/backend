import { Router } from 'express';
import { rules } from '../config.js';
import { POST_CATEGORIES, RELATIONSHIPS, SERVICE_TYPES } from '../models/index.js';
import { COUNTER_CANCEL_REASONS, STATUS_LABELS } from '../services/appointment.service.js';
import * as posts from '../services/post.service.js';
import { firebaseWebConfig, usesFirebase } from '../services/smsGateway.js';
import * as uploads from '../services/upload.service.js';
import { ah } from '../utils/errors.js';
import { paging } from '../utils/validate.js';
import { todayVN } from '../utils/time.js';

const router = Router();

/** Danh mục dùng chung cho giao diện: nội dung khám, khung giờ, trạng thái... */
router.get('/meta', (req, res) => {
  res.json({
    today: todayVN(),
    // Cách gửi mã xác nhận: "firebase" thì giao diện tự gửi qua Firebase với cấu hình này
    otp: { provider: usesFirebase() ? 'firebase' : 'server', firebase: firebaseWebConfig() },
    serviceTypes: Object.entries(SERVICE_TYPES).map(([value, label]) => ({ value, label })),
    slots: rules.booking.slots,
    slotCapacity: rules.booking.slotCapacity,
    userMaxDays: rules.booking.userMaxDays,
    counterMaxDays: rules.booking.counterMaxDays,
    seats: rules.queue.seats,
    priorityEarlyMinutes: rules.queue.priorityEarlyMinutes,
    slotMinutes: rules.booking.slotMinutes,
    maxProfiles: rules.maxProfilesPerAccount,
    relationships: RELATIONSHIPS,
    statusLabels: STATUS_LABELS,
    cancelReasons: COUNTER_CANCEL_REASONS,
    postCategories: Object.entries(POST_CATEGORIES).map(([value, label]) => ({ value, label })),
  });
});

// Ảnh bài viết do Admin tải lên; mã ảnh không đổi nên trình duyệt giữ lâu được
router.get(
  '/uploads/:id',
  ah(async (req, res) => {
    const img = await uploads.getImage(req.params.id);
    res.set({ 'Content-Type': img.contentType, 'Cache-Control': 'public, max-age=31536000, immutable' });
    res.send(img.data);
  }),
);

// ---------- Bài viết công khai (UC.4.1 – UC.4.3), không cần đăng nhập ----------

router.get(
  '/posts',
  ah(async (req, res) => {
    const { page, limit, skip } = paging(req.query, 9);
    const result = await posts.listPublished({ category: req.query.category, q: req.query.q, skip, limit });
    res.json({ ...result, page, limit });
  }),
);

router.get(
  '/posts/:idOrSlug',
  ah(async (req, res) => {
    res.json({ post: await posts.getPublished(req.params.idOrSlug) });
  }),
);

export default router;
