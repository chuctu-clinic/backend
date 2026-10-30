import { rules } from '../config.js';
import { ROLES, STAFF_ROLES, User } from '../models/index.js';
import { badRequest, conflict, notFound } from '../utils/errors.js';
import { escapeRegex } from '../utils/validate.js';
import { hashPassword } from './auth.service.js';
import { assertFresh, createPatient, syncSelfProfile } from './patient.service.js';

async function assertUnique({ phone, email }, excludeId = null) {
  const not = excludeId ? { _id: { $ne: excludeId } } : {};
  if (phone && (await User.exists({ phone, ...not }))) {
    throw conflict('Số điện thoại đã được dùng cho tài khoản khác.', 'PHONE_TAKEN', { fields: { phone: 'Số điện thoại đã được dùng cho tài khoản khác.' } });
  }
  if (email && (await User.exists({ email, ...not }))) {
    throw conflict('Email đã được dùng cho tài khoản khác.', 'EMAIL_TAKEN', { fields: { email: 'Email đã được dùng cho tài khoản khác.' } });
  }
}

export async function listAccounts({ q, role, status, skip, limit }) {
  const filter = {};
  if (q) {
    const rx = new RegExp(escapeRegex(q.trim()), 'i');
    filter.$or = [{ fullName: rx }, { phone: rx }, { email: rx }];
  }
  if (ROLES.includes(role)) filter.role = role;
  if (status === 'active') filter.isActive = true;
  if (status === 'locked') filter.isActive = false;
  const [items, total, counts] = await Promise.all([
    User.find(filter).sort({ createdAt: -1 }).skip(skip).limit(limit),
    User.countDocuments(filter),
    User.aggregate([{ $group: { _id: '$role', n: { $sum: 1 } } }]),
  ]);
  return {
    items: items.map((u) => u.toPublic()),
    total,
    roleCounts: Object.fromEntries(ROLES.map((r) => [r, counts.find((c) => c._id === r)?.n || 0])),
  };
}

export async function getAccount(id) {
  const user = await User.findById(id);
  if (!user) throw notFound('Không tìm thấy tài khoản.');
  return user;
}

/** Admin thêm tài khoản; tài khoản nội bộ phải có mật khẩu tạm tối thiểu 8 ký tự (BR.33). */
export async function createAccount(data) {
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
    passwordHash: internal ? await hashPassword(data.tempPassword) : null,
    mustChangePassword: internal,
  });
  if (user.role === 'USER') {
    await createPatient({ fullName: user.fullName, phone: user.phone }, { userId: user._id, relationship: 'BAN_THAN', createdBy: user._id });
  }
  return user;
}

export async function updateAccount(id, data) {
  const user = await getAccount(id);
  assertFresh(user.infoUpdatedAt ?? user.createdAt, data.expectedUpdatedAt);
  await assertUnique({ phone: data.phone, email: data.email }, user._id);
  for (const key of ['fullName', 'phone', 'address', 'gender']) {
    if (data[key] !== undefined) user[key] = data[key];
  }
  if (data.email !== undefined) user.email = data.email || undefined;
  if (data.dob !== undefined) user.dob = data.dob ? new Date(`${data.dob}T00:00:00Z`) : null;
  user.infoUpdatedAt = new Date();
  await user.save();
  await syncSelfProfile(user);
  return user;
}

/** Khoá tài khoản: không đăng nhập được nữa, phiếu khám đã đặt giữ nguyên (BR.33). */
export async function setAccountActive(admin, id, active) {
  const user = await getAccount(id);
  if (user._id.equals(admin._id)) throw badRequest('Không tự khoá tài khoản đang đăng nhập.');
  user.isActive = active;
  await user.save();
  return user;
}

/** Đổi vai trò. Lên vai trò nội bộ mà chưa có mật khẩu thì cần đặt mật khẩu tạm (BR.32). */
export async function setRole(admin, id, role, tempPassword) {
  if (!ROLES.includes(role)) throw badRequest('Vai trò không hợp lệ.');
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

export async function resetPassword(id, tempPassword) {
  const user = await getAccount(id);
  if (!STAFF_ROLES.includes(user.role)) throw badRequest('Tài khoản người dùng không dùng mật khẩu.');
  user.passwordHash = await hashPassword(tempPassword);
  user.mustChangePassword = true;
  await user.save();
  return user;
}
