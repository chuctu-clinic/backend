import { Appointment, Patient, Post, User } from '../models/index.ts';
import { slotDateValue, todayVN } from '../utils/time.ts';
import { postView } from './post.service.ts';
import { queueState } from './queue.service.ts';

/** Tổng quan trong ngày cho Admin (UC.2.3). */
export async function overview(date = todayVN()) {
  const [byStatus, patients, posts, drafts, users, queue] = await Promise.all([
    Appointment.aggregate<{ _id: string; n: number }>([{ $match: { slotDate: slotDateValue(date) } }, { $group: { _id: '$status', n: { $sum: 1 } } }]),
    Patient.countDocuments(),
    Post.aggregate<{ _id: string; n: number }>([{ $group: { _id: '$status', n: { $sum: 1 } } }]),
    Post.find({ status: 'DRAFT' }).sort({ updatedAt: -1 }).limit(5),
    User.aggregate<{ _id: string; n: number }>([{ $group: { _id: '$role', n: { $sum: 1 } } }]),
    queueState(date),
  ]);
  const statusCounts = Object.fromEntries(byStatus.map((s) => [s._id, s.n]));
  const total = byStatus.reduce((sum, s) => sum + s.n, 0);
  return {
    date,
    appointments: {
      total,
      active: total - (statusCounts.CANCELLED || 0),
      byStatus: statusCounts,
    },
    patients: { total: patients },
    posts: {
      published: posts.find((p) => p._id === 'PUBLISHED')?.n || 0,
      draft: posts.find((p) => p._id === 'DRAFT')?.n || 0,
    },
    accounts: Object.fromEntries(users.map((u) => [u._id, u.n])),
    drafts: drafts.map((p) => postView(p, { full: false })),
    queue: {
      inRoomCount: queue.inRoomCount,
      waitingCount: queue.waitingCount,
      completedCount: queue.completedCount,
      capacity: queue.capacity,
      slotMap: queue.slotMap,
    },
  };
}
