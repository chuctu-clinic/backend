import mongoose from 'mongoose';

// Ảnh Admin tải lên cho bài viết (ảnh thu nhỏ, ảnh trong nội dung). Lưu thẳng trong CSDL
// để chạy được cả khi demo (MongoDB trong bộ nhớ) lẫn khi triển khai mà không cần ổ đĩa riêng.
const uploadSchema = new mongoose.Schema(
  {
    contentType: { type: String, required: true },
    size: { type: Number, required: true },
    data: { type: Buffer, required: true },
    createdBy: { type: mongoose.Schema.Types.ObjectId, ref: 'User', required: true },
  },
  { timestamps: true },
);

export const Upload = mongoose.model('Upload', uploadSchema);
