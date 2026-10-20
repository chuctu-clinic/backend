import mongoose from 'mongoose';

// Bộ đếm tăng nguyên tử để sinh mã bệnh nhân và mã phiếu khám không trùng.
const counterSchema = new mongoose.Schema({
  _id: { type: String },
  seq: { type: Number, default: 0 },
});

export const Counter = mongoose.model('Counter', counterSchema, 'counters');

export async function nextSeq(key) {
  const doc = await Counter.findOneAndUpdate({ _id: key }, { $inc: { seq: 1 } }, { upsert: true, new: true });
  return doc.seq;
}

// Số chỗ đã giữ của từng khung giờ. Giữ chỗ bằng một lệnh cập nhật nguyên tử nên không thể vượt 6 phiếu
// dù nhiều người đặt cùng lúc (QAs6).
const slotCounterSchema = new mongoose.Schema({
  date: { type: String, required: true },
  start: { type: String, required: true },
  count: { type: Number, default: 0 },
});
slotCounterSchema.index({ date: 1, start: 1 }, { unique: true });

export const SlotCounter = mongoose.model('SlotCounter', slotCounterSchema, 'slotCounters');

// Cài đặt vận hành (ví dụ: tự động mời người kế tiếp vào ghế trống).
const settingSchema = new mongoose.Schema({
  _id: { type: String },
  value: { type: mongoose.Schema.Types.Mixed },
});

export const Setting = mongoose.model('Setting', settingSchema, 'settings');

export async function getSetting(key, fallback) {
  const doc = await Setting.findById(key).lean();
  return doc ? doc.value : fallback;
}

export async function setSetting(key, value) {
  await Setting.updateOne({ _id: key }, { $set: { value } }, { upsert: true });
  return value;
}
