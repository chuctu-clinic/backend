import mongoose from 'mongoose';

// Chuyên mục bài viết. Các trang Giới thiệu, Dịch vụ, Hướng dẫn, Liên hệ là bài viết có slug cố định.
export const POST_CATEGORIES = {
  TIN_TUC: 'Tin tức',
  HUONG_DAN: 'Hướng dẫn',
  THIEN_NGUYEN: 'Thiện nguyện',
  SU_KIEN: 'Sự kiện',
  TRANG: 'Trang thông tin',
};

export const PAGE_SLUGS = ['gioi-thieu', 'dich-vu', 'huong-dan', 'lien-he'];

const postSchema = new mongoose.Schema(
  {
    title: { type: String, required: true, trim: true },
    slug: { type: String, trim: true },
    category: { type: String, enum: Object.keys(POST_CATEGORIES), required: true },
    summary: { type: String, default: '' },
    thumbnailUrl: { type: String, default: null },
    authorId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', required: true },
    authorName: { type: String, required: true },
    content: { type: String, default: '' },
    // HIDDEN: đã công khai rồi tạm ẩn khỏi website, giữ nguyên ngày đăng
    status: { type: String, enum: ['DRAFT', 'PUBLISHED', 'HIDDEN'], default: 'DRAFT' },
    publishedAt: { type: Date, default: null },
    // Chỉ có giá trị true, bỏ ghim thì xoá trường: bài cũ không có trường này nên false sẽ xếp lệch với chúng
    pinned: { type: Boolean },
  },
  { timestamps: true },
);

postSchema.index({ slug: 1 }, { unique: true, partialFilterExpression: { slug: { $type: 'string' } } });
// Mỗi lúc chỉ một bài được ghim
postSchema.index({ pinned: 1 }, { unique: true, partialFilterExpression: { pinned: true } });
postSchema.index({ status: 1, category: 1, publishedAt: -1 });

export const Post = mongoose.model('Post', postSchema);
