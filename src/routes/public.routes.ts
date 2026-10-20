import { Router } from 'express';
import { rules } from '../config.ts';
import { POST_CATEGORIES, RELATIONSHIPS, SERVICE_TYPES } from '../models/index.ts';
import { COUNTER_CANCEL_REASONS, STATUS_LABELS } from '../services/appointment.service.ts';
import * as posts from '../services/post.service.ts';
import { ah } from '../utils/errors.ts';
import { paging, str } from '../utils/validate.ts';
import { todayVN } from '../utils/time.ts';

const router = Router();

/** Danh mục dùng chung cho giao diện: nội dung khám, khung giờ, trạng thái... */
router.get('/meta', (req, res) => {
  res.json({
    today: todayVN(),
    serviceTypes: Object.entries(SERVICE_TYPES).map(([value, label]) => ({ value, label })),
    slots: rules.booking.slots,
    slotCapacity: rules.booking.slotCapacity,
    userMaxDays: rules.booking.userMaxDays,
    counterMaxDays: rules.booking.counterMaxDays,
    seats: rules.queue.seats,
    maxProfiles: rules.maxProfilesPerAccount,
    relationships: RELATIONSHIPS,
    statusLabels: STATUS_LABELS,
    cancelReasons: COUNTER_CANCEL_REASONS,
    postCategories: Object.entries(POST_CATEGORIES).map(([value, label]) => ({ value, label })),
  });
});

// ---------- Bài viết công khai (UC.4.1 – UC.4.3), không cần đăng nhập ----------

router.get(
  '/posts',
  ah(async (req, res) => {
    const { page, limit, skip } = paging(req.query, 9);
    const result = await posts.listPublished({ category: str(req.query.category), q: str(req.query.q), skip, limit });
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
