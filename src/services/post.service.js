import { Post, POST_CATEGORIES, PAGE_SLUGS } from '../models/index.js';
import { badRequest, notFound } from '../utils/errors.js';
import { escapeRegex } from '../utils/validate.js';

export function postView(p, { full = true } = {}) {
  return {
    id: p._id.toString(),
    title: p.title,
    slug: p.slug ?? null,
    category: p.category,
    categoryLabel: POST_CATEGORIES[p.category],
    summary: p.summary,
    thumbnailUrl: p.thumbnailUrl,
    authorName: p.authorName,
    status: p.status,
    pinned: Boolean(p.pinned),
    publishedAt: p.publishedAt,
    createdAt: p.createdAt,
    updatedAt: p.updatedAt,
    ...(full ? { content: p.content } : {}),
  };
}

// ---------- Công khai: chỉ bài đã đăng (BR.08) ----------

export async function listPublished({ category, q, skip, limit, withFeatured = false }) {
  const filter = { status: 'PUBLISHED', category: { $ne: 'TRANG' } };
  if (category && POST_CATEGORIES[category]) filter.category = category;
  if (q) filter.title = new RegExp(escapeRegex(q.trim()), 'i');
  // Bài nổi bật đứng yên ở mọi trang: bài ghim, chưa ghim thì bài mới nhất; phân trang chỉ chia các bài còn lại
  const featured = withFeatured ? await Post.findOne(filter).sort({ pinned: -1, publishedAt: -1 }) : null;
  const rest = featured ? { ...filter, _id: { $ne: featured._id } } : filter;
  const [items, total] = await Promise.all([
    Post.find(rest).sort({ publishedAt: -1 }).skip(skip).limit(limit),
    Post.countDocuments(filter),
  ]);
  return { featured: featured && postView(featured, { full: false }), items: items.map((p) => postView(p, { full: false })), total };
}

export async function getPublished(idOrSlug) {
  const filter = /^[a-f\d]{24}$/i.test(idOrSlug) ? { _id: idOrSlug } : { slug: idOrSlug };
  const post = await Post.findOne({ ...filter, status: 'PUBLISHED' });
  if (!post) throw notFound('Bài viết không tồn tại hoặc chưa được đăng.');
  return postView(post);
}

// ---------- Quản trị (BR.09, BR.10) ----------

function assertPublishable(data) {
  const missing = {};
  if (!data.title) missing.title = 'Bài viết cần có tiêu đề.';
  if (!data.summary || !data.summary.trim()) missing.summary = 'Bài viết cần có mô tả.';
  if (!data.category) missing.category = 'Bài viết cần có chuyên mục.';
  if (!data.authorName) missing.authorName = 'Bài viết cần có tác giả.';
  if (!data.content || !data.content.trim()) missing.content = 'Bài viết cần có nội dung.';
  if (Object.keys(missing).length) {
    throw badRequest('Bài viết còn thiếu thông tin nên chưa đăng được.', { fields: missing }, 'NOT_PUBLISHABLE');
  }
}

// Trang thông tin cố định (Giới thiệu, Dịch vụ…) không hiện trong danh sách quản trị: website chỉ có 4 danh mục bài viết
export async function adminList({ status, category, q, skip, limit }) {
  const filter = { category: { $ne: 'TRANG' } };
  if (['DRAFT', 'PUBLISHED', 'HIDDEN'].includes(status)) filter.status = status;
  if (category && category !== 'TRANG' && POST_CATEGORIES[category]) filter.category = category;
  if (q) filter.title = new RegExp(escapeRegex(q.trim()), 'i');
  const [items, total] = await Promise.all([
    // Xếp theo ngày tạo: bài mới vào đầu danh sách, sửa, ẩn hay ghim bài không làm các trang xáo trộn
    Post.find(filter).sort({ createdAt: -1, _id: -1 }).skip(skip).limit(limit),
    Post.countDocuments(filter),
  ]);
  const counts = await Post.aggregate([{ $match: { category: { $ne: 'TRANG' } } }, { $group: { _id: '$status', n: { $sum: 1 } } }]);
  const statusCounts = Object.fromEntries(['PUBLISHED', 'DRAFT', 'HIDDEN'].map((s) => [s, counts.find((c) => c._id === s)?.n || 0]));
  return { items: items.map((p) => postView(p, { full: false })), total, statusCounts };
}

export async function adminGet(id) {
  const post = await Post.findById(id);
  if (!post) throw notFound('Không tìm thấy bài viết.');
  return postView(post);
}

export async function createPost(admin, data) {
  const doc = {
    title: data.title,
    category: data.category,
    summary: data.summary ?? '',
    thumbnailUrl: data.thumbnailUrl ?? null,
    content: data.content ?? '',
    authorId: admin._id,
    authorName: data.authorName || admin.fullName,
    status: data.status,
    // Bản nháp cũng giữ ngày đăng đã chọn, để khi công khai dùng đúng ngày đó
    publishedAt: data.publishedAt ? new Date(data.publishedAt) : null,
  };
  if (data.status === 'PUBLISHED') {
    assertPublishable(doc);
    doc.publishedAt ??= new Date();
  }
  return postView(await Post.create(doc));
}

export async function updatePost(id, data) {
  const post = await Post.findById(id);
  if (!post) throw notFound('Không tìm thấy bài viết.');
  for (const key of ['title', 'category', 'summary', 'thumbnailUrl', 'content', 'authorName']) {
    if (data[key] !== undefined) post[key] = data[key];
  }
  if (post.slug && PAGE_SLUGS.includes(post.slug)) post.category = 'TRANG';
  if (data.status) post.status = data.status;
  if (data.publishedAt) post.publishedAt = new Date(data.publishedAt);
  if (post.status === 'PUBLISHED') {
    assertPublishable(post);
    if (!post.publishedAt) post.publishedAt = new Date();
  } else {
    // Bài rời website thì thôi ghim, để lúc công khai lại không tự chiếm chỗ bài đang ghim
    post.pinned = undefined;
  }
  await post.save();
  return postView(post);
}

export async function setPinned(id, pinned) {
  const post = await Post.findById(id);
  if (!post) throw notFound('Không tìm thấy bài viết.');
  if (pinned && (post.status !== 'PUBLISHED' || post.category === 'TRANG')) {
    throw badRequest('Chỉ ghim được bài viết đã công khai.', undefined, 'NOT_PINNABLE');
  }
  // Không đổi giờ cập nhật: ghim không phải là sửa bài
  await Post.updateMany(pinned ? { pinned: true } : { _id: post._id }, { $unset: { pinned: 1 } }, { timestamps: false });
  if (pinned) await Post.updateOne({ _id: post._id }, { $set: { pinned: true } }, { timestamps: false });
  return postView(await Post.findById(id));
}

export async function deletePost(id) {
  const post = await Post.findById(id);
  if (!post) throw notFound('Không tìm thấy bài viết.');
  if (post.slug && PAGE_SLUGS.includes(post.slug)) {
    throw badRequest('Đây là trang thông tin cố định của website, chỉ sửa được, không xoá được.');
  }
  await post.deleteOne();
  return { deleted: true };
}
