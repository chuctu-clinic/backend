import mongoose from 'mongoose';

const verificationCodeSchema = new mongoose.Schema({
  phone: { type: String, required: true },
  codeHash: { type: String, required: true },
  purpose: { type: String, enum: ['REGISTER', 'NEW_DEVICE'] as const, required: true },
  registerData: { type: mongoose.Schema.Types.Mixed, default: null },
  attempts: { type: Number, default: 0 },
  expiresAt: { type: Date, required: true },
  usedAt: { type: Date, default: null },
  createdAt: { type: Date, default: Date.now },
});

verificationCodeSchema.index({ phone: 1, createdAt: -1 });
// Giữ bản ghi thêm 1 giờ sau khi hết hạn để vẫn đếm được giới hạn 3 mã mỗi giờ, rồi tự xoá.
verificationCodeSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 3600 });

export const VerificationCode = mongoose.model('VerificationCode', verificationCodeSchema, 'verificationCodes');
