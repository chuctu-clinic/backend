import type { FilterQuery } from 'mongoose';
import { Post, POST_CATEGORIES, PAGE_SLUGS, type PostCategory, type PostDoc, type UserDoc } from '../models/index.ts';
import { badRequest, notFound } from '../utils/errors.ts';
import { escapeRegex, type Paging } from '../utils/validate.ts';

export type PostStatus = 'DRAFT' | 'PUBLISHED';

export interface PostInput {
  title?: string;
  category?: PostCategory;
  summary?: string;
  thumbnailUrl?: string | null;
  authorName?: string;
  publishedAt?: string | null;
  content?: string;
  status?: PostStatus;
}

const isCategory = (c: string | undefined): c is PostCategory => !!c && c in POST_CATEGORIES;

export function postView(p: PostDoc, { full = true } = {}) {
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
    publishedAt: p.publishedAt,
    createdAt: p.createdAt,
    updatedAt: p.updatedAt,
    ...(full ? { content: p.content } : {}),
  };
}

// ---------- Công khai: chỉ bài đã đăng (BR.08) ----------

export async function listPublished({ category, q, skip, limit }: { category?: string; q?: string } & Paging) {
  const filter: FilterQuery<PostDoc> = { status: 'PUBLISHED', category: { $ne: 'TRANG' } };
  if (isCategory(category)) filter.category = category;
  if (q) filter.title = new RegExp(escapeRegex(q.trim()), 'i');
  const [items, total] = await Promise.all([
    Post.find(filter).sort({ publishedAt: -1 }).skip(skip).limit(limit),
    Post.countDocuments(filter),
  ]);
  return { items: items.map((p) => postView(p, { full: false })), total };
}

export async function getPublished(idOrSlug: string) {
  const filter = /^[a-f\d]{24}$/i.test(idOrSlug) ? { _id: idOrSlug } : { slug: idOrSlug };
  const post = await Post.findOne({ ...filter, status: 'PUBLISHED' });
  if (!post) throw notFound('Bài viết không tồn tại hoặc chưa được đăng.');
  return postView(post);
}

// ---------- Quản trị (BR.09, BR.10) ----------

function assertPublishable(data: { title?: string | null; category?: string | null; authorName?: string | null; content?: string | null }) {
  const missing: Record<string, string> = {};
  if (!data.title) missing.title = 'Bài viết cần có tiêu đề.';
  if (!data.category) missing.category = 'Bài viết cần có chuyên mục.';
  if (!data.authorName) missing.authorName = 'Bài viết cần có tác giả.';
  if (!data.content || !data.content.trim()) missing.content = 'Bài viết cần có nội dung.';
  if (Object.keys(missing).length) {
    throw badRequest('Bài viết còn thiếu thông tin nên chưa đăng được.', { fields: missing }, 'NOT_PUBLISHABLE');
  }
}

export async function adminList({ status, category, q, skip, limit }: { status?: string; category?: string; q?: string } & Paging) {
  const filter: FilterQuery<PostDoc> = {};
  if (status === 'DRAFT' || status === 'PUBLISHED') filter.status = status;
  if (isCategory(category)) filter.category = category;
  if (q) filter.title = new RegExp(escapeRegex(q.trim()), 'i');
  const [items, total] = await Promise.all([
    Post.find(filter).sort({ updatedAt: -1 }).skip(skip).limit(limit),
    Post.countDocuments(filter),
  ]);
  return { items: items.map((p) => postView(p, { full: false })), total };
}

export async function adminGet(id: string) {
  const post = await Post.findById(id);
  if (!post) throw notFound('Không tìm thấy bài viết.');
  return postView(post);
}

export async function createPost(admin: UserDoc, data: PostInput & { title: string; category: PostCategory; status: PostStatus }) {
  const doc = {
    title: data.title,
    category: data.category,
    summary: data.summary ?? '',
    thumbnailUrl: data.thumbnailUrl ?? null,
    content: data.content ?? '',
    authorId: admin._id,
    authorName: data.authorName || admin.fullName,
    status: data.status,
    publishedAt: null as Date | null,
  };
  if (data.status === 'PUBLISHED') {
    assertPublishable(doc);
    doc.publishedAt = data.publishedAt ? new Date(data.publishedAt) : new Date();
  }
  return postView(await Post.create(doc));
}

const EDITABLE = ['title', 'category', 'summary', 'thumbnailUrl', 'content', 'authorName'] as const;

export async function updatePost(id: string, data: PostInput) {
  const post = await Post.findById(id);
  if (!post) throw notFound('Không tìm thấy bài viết.');
  for (const key of EDITABLE) {
    if (data[key] !== undefined) post.set(key, data[key]);
  }
  if (post.slug && PAGE_SLUGS.includes(post.slug)) post.category = 'TRANG';
  if (data.status) post.status = data.status;
  if (post.status === 'PUBLISHED') {
    assertPublishable(post);
    if (data.publishedAt) post.publishedAt = new Date(data.publishedAt);
    else if (!post.publishedAt) post.publishedAt = new Date();
  }
  await post.save();
  return postView(post);
}

export async function deletePost(id: string) {
  const post = await Post.findById(id);
  if (!post) throw notFound('Không tìm thấy bài viết.');
  if (post.slug && PAGE_SLUGS.includes(post.slug)) {
    throw badRequest('Đây là trang thông tin cố định của website, chỉ sửa được, không xoá được.');
  }
  await post.deleteOne();
  return { deleted: true };
}
