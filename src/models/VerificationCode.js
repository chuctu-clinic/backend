import mongoose from 'mongoose';

const verificationCodeSchema = new mongoose.Schema({
  phone: { type: String, required: true },
  // Địa chỉ nhận mã khi gửi qua email (null: gửi tin nhắn tới số điện thoại); dùng để đếm giới hạn mỗi email
  email: { type: String, default: null },
  codeHash: { type: String, required: true },
  purpose: { type: String, enum: ['REGISTER', 'NEW_DEVICE', 'RESET_PASSWORD', 'CHANGE_EMAIL'], required: true },
  registerData: { type: Object, default: null },
  attempts: { type: Number, default: 0 },
  expiresAt: { type: Date, required: true },
  usedAt: { type: Date, default: null },
  createdAt: { type: Date, default: Date.now },
});

verificationCodeSchema.index({ phone: 1, createdAt: -1 });
verificationCodeSchema.index({ email: 1, createdAt: -1 });
// Giữ bản ghi thêm 1 giờ sau khi hết hạn để vẫn đếm được giới hạn mã mỗi giờ, rồi tự xoá.
verificationCodeSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 3600 });

export const VerificationCode = mongoose.model('VerificationCode', verificationCodeSchema, 'verificationCodes');
