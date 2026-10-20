import mongoose from 'mongoose';

// Chuyên mục bài viết. Các trang Giới thiệu, Dịch vụ, Hướng dẫn, Liên hệ là bài viết có slug cố định.
export const POST_CATEGORY_KEYS = ['TIN_TUC', 'HUONG_DAN', 'THIEN_NGUYEN', 'SU_KIEN', 'TRANG'] as const;
export type PostCategory = (typeof POST_CATEGORY_KEYS)[number];

export const POST_CATEGORIES: Record<PostCategory, string> = {
  TIN_TUC: 'Tin tức',
  HUONG_DAN: 'Hướng dẫn',
  THIEN_NGUYEN: 'Thiện nguyện',
  SU_KIEN: 'Sự kiện',
  TRANG: 'Trang thông tin',
};

export const PAGE_SLUGS: readonly string[] = ['gioi-thieu', 'dich-vu', 'huong-dan', 'lien-he'];

const postSchema = new mongoose.Schema(
  {
    title: { type: String, required: true, trim: true },
    slug: { type: String, trim: true },
    category: { type: String, enum: POST_CATEGORY_KEYS, required: true },
    summary: { type: String, default: '' },
    thumbnailUrl: { type: String, default: null },
    authorId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', required: true },
    authorName: { type: String, required: true },
    content: { type: String, default: '' },
    status: { type: String, enum: ['DRAFT', 'PUBLISHED'] as const, default: 'DRAFT' },
    publishedAt: { type: Date, default: null },
  },
  { timestamps: true },
);

postSchema.index({ slug: 1 }, { unique: true, partialFilterExpression: { slug: { $type: 'string' } } });
postSchema.index({ status: 1, category: 1, publishedAt: -1 });

export const Post = mongoose.model('Post', postSchema);
export type PostDoc = ReturnType<(typeof Post)['hydrate']>;
