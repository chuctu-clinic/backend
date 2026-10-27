import mongoose from 'mongoose';
import { GENDERS, ROLES, STAFF_ROLES } from '../shared/constants.js';

export { ROLES, STAFF_ROLES };

const userSchema = new mongoose.Schema(
  {
    phone: { type: String, required: true, unique: true, trim: true },
    email: { type: String, trim: true, lowercase: true },
    passwordHash: { type: String, default: null, select: false },
    mustChangePassword: { type: Boolean, default: false },
    fullName: { type: String, required: true, trim: true },
    dob: { type: Date, default: null },
    gender: { type: String, enum: [...Object.keys(GENDERS), null], default: null },
    address: { type: String, default: null },
    role: { type: String, enum: ROLES, required: true, default: 'USER' },
    isActive: { type: Boolean, default: true },
    lastLoginAt: { type: Date, default: null },
  },
  { timestamps: true },
);

// email nếu có thì phải duy nhất
userSchema.index({ email: 1 }, { unique: true, partialFilterExpression: { email: { $type: 'string' } } });
userSchema.index({ role: 1, createdAt: -1 });

userSchema.methods.toPublic = function toPublic() {
  return {
    id: this._id.toString(),
    phone: this.phone,
    email: this.email ?? null,
    fullName: this.fullName,
    dob: this.dob,
    gender: this.gender,
    address: this.address,
    role: this.role,
    isActive: this.isActive,
    mustChangePassword: this.mustChangePassword,
    lastLoginAt: this.lastLoginAt,
    createdAt: this.createdAt,
  };
};

export const User = mongoose.model('User', userSchema);
