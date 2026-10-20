import mongoose from 'mongoose';

const trustedDeviceSchema = new mongoose.Schema({
  userId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', required: true },
  deviceHash: { type: String, required: true },
  createdAt: { type: Date, default: Date.now },
  lastUsedAt: { type: Date, default: null },
});

trustedDeviceSchema.index({ userId: 1, deviceHash: 1 }, { unique: true });

export const TrustedDevice = mongoose.model('TrustedDevice', trustedDeviceSchema, 'trustedDevices');
