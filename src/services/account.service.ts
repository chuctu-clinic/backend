import type { FilterQuery, Types } from 'mongoose';
import { rules } from '../config.ts';
import { ROLES, STAFF_ROLES, User, type UserDoc } from '../models/index.ts';
import type { Gender, Role } from '../shared/constants.ts';
import { badRequest, conflict, notFound } from '../utils/errors.ts';
import { escapeRegex, type Paging } from '../utils/validate.ts';
import { hashPassword } from './auth.service.ts';
import { createPatient } from './patient.service.ts';

const isRole = (r: string | undefined): r is Role => ROLES.some((x) => x === r);

async function assertUnique({ phone, email }: { phone?: string; email?: string | null }, excludeId: Types.ObjectId | null = null) {
  const not = excludeId ? { _id: { $ne: excludeId } } : {};
  if (phone && (await User.exists({ phone, ...not }))) {
    throw conflict('Số điện thoại đã được dùng cho tài khoản khác.', 'PHONE_TAKEN', { fields: { phone: 'Số điện thoại đã được dùng cho tài khoản khác.' } });
  }
  if (email && (await User.exists({ email, ...not }))) {
    throw conflict('Email đã được dùng cho tài khoản khác.', 'EMAIL_TAKEN', { fields: { email: 'Email đã được dùng cho tài khoản khác.' } });
  }
}

export async function listAccounts({ q, role, status, skip, limit }: { q?: string; role?: string; status?: string } & Paging) {
  const filter: FilterQuery<UserDoc> = {};
  if (q) {
    const rx = new RegExp(escapeRegex(q.trim()), 'i');
    filter.$or = [{ fullName: rx }, { phone: rx }, { email: rx }];
  }
  if (isRole(role)) filter.role = role;
  if (status === 'active') filter.isActive = true;
  if (status === 'locked') filter.isActive = false;
  const [items, total, counts] = await Promise.all([
    User.find(filter).sort({ createdAt: -1 }).skip(skip).limit(limit),
    User.countDocuments(filter),
    User.aggregate<{ _id: Role; n: number }>([{ $group: { _id: '$role', n: { $sum: 1 } } }]),
  ]);
  return {
    items: items.map((u) => u.toPublic()),
    total,
    roleCounts: Object.fromEntries(ROLES.map((r) => [r, counts.find((c) => c._id === r)?.n || 0])),
  };
}

export async function getAccount(id: string) {
  const user = await User.findById(id);
  if (!user) throw notFound('Không tìm thấy tài khoản.');
  return user;
}

/** Admin thêm tài khoản; tài khoản nội bộ phải có mật khẩu tạm tối thiểu 8 ký tự (BR.33). */
export async function createAccount(data: { fullName: string; phone: string; email: string | null; role: Role; tempPassword?: string }) {
  await assertUnique(data);
  const internal = STAFF_ROLES.includes(data.role);
  if (internal && (!data.tempPassword || data.tempPassword.length < rules.minPasswordLength)) {
    throw badRequest(`Mật khẩu tạm cần tối thiểu ${rules.minPasswordLength} ký tự.`, {
      fields: { tempPassword: `Mật khẩu tạm cần tối thiểu ${rules.minPasswordLength} ký tự.` },
    });
  }
  const user = await User.create({
    phone: data.phone,
    email: data.email || undefined,
    fullName: data.fullName,
    role: data.role,
    passwordHash: internal && data.tempPassword ? await hashPassword(data.tempPassword) : null,
    mustChangePassword: internal,
  });
  if (user.role === 'USER') {
    await createPatient({ fullName: user.fullName, phone: user.phone }, { userId: user._id, relationship: 'BAN_THAN', createdBy: user._id });
  }
  return user;
}

export interface AccountUpdate {
  fullName?: string;
  phone?: string;
  email?: string | null;
  address?: string | null;
  gender?: Gender | null;
  dob?: string | null;
}

const EDITABLE = ['fullName', 'phone', 'address', 'gender'] as const;

export async function updateAccount(id: string, data: AccountUpdate) {
  const user = await getAccount(id);
  await assertUnique({ phone: data.phone, email: data.email }, user._id);
  for (const key of EDITABLE) {
    if (data[key] !== undefined) user.set(key, data[key]);
  }
  if (data.email !== undefined) user.email = data.email || undefined;
  if (data.dob !== undefined) user.dob = data.dob ? new Date(`${data.dob}T00:00:00Z`) : null;
  await user.save();
  return user;
}

/** Khoá tài khoản: không đăng nhập được nữa, phiếu khám đã đặt giữ nguyên (BR.33). */
export async function setAccountActive(admin: UserDoc, id: string, active: boolean) {
  const user = await getAccount(id);
  if (user._id.equals(admin._id)) throw badRequest('Không tự khoá tài khoản đang đăng nhập.');
  user.isActive = active;
  await user.save();
  return user;
}

/** Đổi vai trò. Lên vai trò nội bộ mà chưa có mật khẩu thì cần đặt mật khẩu tạm (BR.32). */
export async function setRole(admin: UserDoc, id: string, role: Role, tempPassword?: string) {
  if (!isRole(role)) throw badRequest('Vai trò không hợp lệ.');
  const user = await User.findById(id).select('+passwordHash');
  if (!user) throw notFound('Không tìm thấy tài khoản.');
  if (user._id.equals(admin._id) && role !== 'ADMIN') throw badRequest('Không tự hạ quyền tài khoản đang đăng nhập.');
  if (STAFF_ROLES.includes(role) && !user.passwordHash) {
    if (!tempPassword || tempPassword.length < rules.minPasswordLength) {
      throw badRequest(`Tài khoản nội bộ cần mật khẩu tạm tối thiểu ${rules.minPasswordLength} ký tự.`, {
        fields: { tempPassword: `Cần mật khẩu tạm tối thiểu ${rules.minPasswordLength} ký tự.` },
      }, 'PASSWORD_REQUIRED');
    }
    user.passwordHash = await hashPassword(tempPassword);
    user.mustChangePassword = true;
  }
  user.role = role;
  await user.save();
  return user;
}

export async function resetPassword(id: string, tempPassword: string) {
  const user = await getAccount(id);
  if (!STAFF_ROLES.includes(user.role)) throw badRequest('Tài khoản người dùng không dùng mật khẩu.');
  user.passwordHash = await hashPassword(tempPassword);
  user.mustChangePassword = true;
  await user.save();
  return user;
}
