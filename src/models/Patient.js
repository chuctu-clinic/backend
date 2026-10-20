import mongoose from 'mongoose';
import { GENDERS, RELATIONSHIPS as RELATIONSHIP_LABELS } from '@chuctu/shared/constants.js';

export const RELATIONSHIPS = Object.keys(RELATIONSHIP_LABELS);

const patientSchema = new mongoose.Schema(
  {
    patientCode: { type: String, required: true, unique: true },
    userId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', default: null },
    relationship: { type: String, enum: [...RELATIONSHIPS, null], default: null },
    fullName: { type: String, required: true, trim: true },
    dob: { type: Date, default: null },
    birthYear: { type: Number, default: null },
    gender: { type: String, enum: [...Object.keys(GENDERS), null], default: null },
    phone: { type: String, default: null },
    address: { type: String, default: null },
    isLocked: { type: Boolean, default: false },
    lockedAt: { type: Date, default: null },
    createdBy: { type: mongoose.Schema.Types.ObjectId, ref: 'User', default: null },
  },
  { timestamps: true },
);

patientSchema.index({ userId: 1 });
patientSchema.index({ phone: 1 });
patientSchema.index({ fullName: 'text', patientCode: 'text', phone: 'text' });

patientSchema.methods.toPublic = function toPublic() {
  return {
    id: this._id.toString(),
    patientCode: this.patientCode,
    userId: this.userId ? this.userId.toString() : null,
    relationship: this.relationship,
    fullName: this.fullName,
    dob: this.dob,
    birthYear: this.birthYear ?? (this.dob ? this.dob.getUTCFullYear() : null),
    gender: this.gender,
    phone: this.phone,
    address: this.address,
    isLocked: this.isLocked,
    lockedAt: this.lockedAt,
    createdAt: this.createdAt,
  };
};

export const Patient = mongoose.model('Patient', patientSchema);
