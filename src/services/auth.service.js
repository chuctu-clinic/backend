import crypto from 'node:crypto';
import bcrypt from 'bcryptjs';
import { config, rules } from '../config.js';
import { TrustedDevice, User, VerificationCode, STAFF_ROLES } from '../models/index.js';
import { badRequest, conflict, forbidden, notFound, tooMany, unauthorized } from '../utils/errors.js';
import { exposeCodeForDev, sendVerificationCode } from './smsGateway.js';
import { createPatient } from './patient.service.js';

const hmac = (value) => crypto.createHmac('sha256', config.jwtSecret).update(value).digest('hex');
const hashCode = (phone, purpose, code) => hmac(`${phone}:${purpose}:${code}`);
export const hashDevice = (deviceId) => hmac(`device:${deviceId}`);
export const newDeviceId = () => crypto.randomBytes(32).toString('base64url');

function randomCode() {
  return String(crypto.randomInt(0, 10 ** rules.otp.length)).padStart(rules.otp.length, '0');
}

/** Gửi mã xác nhận, áp dụng giới hạn 3 mã mỗi giờ và chờ 60 giây giữa hai lần gửi (BR.03). */
export async function issueCode(phone, purpose, registerData = null) {
  const now = Date.now();
  const recent = await VerificationCode.find({ phone, createdAt: { $gte: new Date(now - 3600_000) } })
    .sort({ createdAt: -1 })
    .lean();

  // Chờ 60 giây áp dụng cho việc gửi lại cùng một loại mã; giới hạn 3 mã mỗi giờ tính chung cho số điện thoại
  const lastSamePurpose = recent.find((c) => c.purpose === purpose);
  if (lastSamePurpose) {
    const waitSec = Math.ceil((lastSamePurpose.createdAt.getTime() + rules.otp.resendSeconds * 1000 - now) / 1000);
    if (waitSec > 0) {
      throw tooMany(`Cô chú vui lòng chờ ${waitSec} giây rồi bấm gửi lại mã.`, { retryAfter: waitSec });
    }
  }
  if (recent.length >= rules.otp.maxPerHour) {
    const oldest = recent[recent.length - 1].createdAt.getTime();
    const waitMin = Math.ceil((oldest + 3600_000 - now) / 60_000);
    throw tooMany(
      `Số điện thoại này đã nhận đủ ${rules.otp.maxPerHour} mã trong một giờ. Cô chú vui lòng thử lại sau khoảng ${waitMin} phút hoặc gọi phòng khám để được giúp.`,
      { retryAfter: waitMin * 60 },
    );
  }

  // Mã cũ chưa dùng của cùng mục đích hết hiệu lực khi có mã mới
  await VerificationCode.updateMany(
    { phone, purpose, usedAt: null, expiresAt: { $gt: new Date(now) } },
    { $set: { expiresAt: new Date(now) } },
  );

  const code = randomCode();
  await VerificationCode.create({
    phone,
    purpose,
    codeHash: hashCode(phone, purpose, code),
    registerData,
    expiresAt: new Date(now + rules.otp.ttlMinutes * 60_000),
    createdAt: new Date(now),
  });
  await sendVerificationCode(phone, code, purpose);

  return {
    sent: true,
    expiresInSeconds: rules.otp.ttlMinutes * 60,
    resendAfterSeconds: rules.otp.resendSeconds,
    ...(exposeCodeForDev() ? { devCode: code } : {}),
  };
}

/** Kiểm tra mã; mã đúng chỉ dùng được một lần. */
export async function verifyCode(phone, purpose, code) {
  const now = new Date();
  const doc = await VerificationCode.findOne({ phone, purpose, usedAt: null, expiresAt: { $gt: now } }).sort({
    createdAt: -1,
  });
  if (!doc) throw badRequest('Mã xác nhận đã hết hạn. Cô chú bấm "Gửi lại mã" để nhận mã mới.', null, 'CODE_EXPIRED');
  if (doc.attempts >= rules.otp.maxAttempts) {
    throw badRequest('Cô chú đã nhập sai quá nhiều lần. Vui lòng bấm "Gửi lại mã" để nhận mã mới.', null, 'CODE_LOCKED');
  }

  const ok = crypto.timingSafeEqual(Buffer.from(doc.codeHash), Buffer.from(hashCode(phone, purpose, String(code))));
  if (!ok) {
    doc.attempts += 1;
    await doc.save();
    const left = rules.otp.maxAttempts - doc.attempts;
    throw badRequest(
      left > 0 ? `Mã xác nhận chưa đúng. Cô chú còn ${left} lần nhập.` : 'Mã xác nhận chưa đúng. Cô chú bấm "Gửi lại mã" để nhận mã mới.',
      { fields: { code: 'Mã xác nhận chưa đúng.' } },
      'CODE_WRONG',
    );
  }

  const used = await VerificationCode.findOneAndUpdate(
    { _id: doc._id, usedAt: null },
    { $set: { usedAt: now } },
    { new: true },
  );
  if (!used) throw badRequest('Mã xác nhận đã được dùng. Cô chú bấm "Gửi lại mã".', null, 'CODE_USED');
  return used;
}

// ---------- Người dùng: đăng ký ----------

export async function startRegister(data) {
  const existing = await User.exists({ phone: data.phone });
  if (existing) {
    throw conflict('Số điện thoại này đã có tài khoản. Cô chú bấm "Đăng nhập" để vào tài khoản.', 'PHONE_TAKEN', {
      fields: { phone: 'Số điện thoại này đã có tài khoản.' },
    });
  }
  return issueCode(data.phone, 'REGISTER', data);
}

export async function completeRegister(phone, code) {
  const doc = await verifyCode(phone, 'REGISTER', code);
  const data = doc.registerData;
  if (!data) throw badRequest('Không tìm thấy thông tin đăng ký. Cô chú vui lòng đăng ký lại.');
  if (await User.exists({ phone })) throw conflict('Số điện thoại này đã có tài khoản.', 'PHONE_TAKEN');

  const user = await User.create({
    phone,
    fullName: data.fullName,
    dob: data.dob ? new Date(`${data.dob}T00:00:00Z`) : null,
    gender: data.gender ?? null,
    address: data.address ?? null,
    role: 'USER',
    lastLoginAt: new Date(),
  });
  // Hồ sơ khám của chính chủ tài khoản được tạo sẵn
  await createPatient(
    {
      fullName: user.fullName,
      dob: data.dob ?? null,
      gender: user.gender,
      phone: user.phone,
      address: user.address,
    },
    { userId: user._id, relationship: 'BAN_THAN', createdBy: user._id },
  );
  return user;
}

// ---------- Người dùng: đăng nhập không mật khẩu ----------

async function isTrusted(userId, deviceId) {
  if (!deviceId) return false;
  const device = await TrustedDevice.findOneAndUpdate(
    { userId, deviceHash: hashDevice(deviceId) },
    { $set: { lastUsedAt: new Date() } },
  );
  return Boolean(device);
}

export async function trustDevice(userId, deviceId) {
  await TrustedDevice.updateOne(
    { userId, deviceHash: hashDevice(deviceId) },
    { $setOnInsert: { createdAt: new Date() }, $set: { lastUsedAt: new Date() } },
    { upsert: true },
  );
}

async function findLoginUser(phone) {
  const user = await User.findOne({ phone });
  if (!user || user.role !== 'USER') {
    throw notFound('Số điện thoại này chưa có tài khoản. Cô chú bấm "Tạo tài khoản" để đăng ký.');
  }
  if (!user.isActive) throw forbidden('Tài khoản đang bị khoá. Cô chú vui lòng gọi phòng khám để được hỗ trợ.');
  return user;
}

/** Thiết bị đã xác nhận thì đăng nhập ngay; thiết bị mới phải nhập mã (BR.02). */
export async function startLogin(phone, deviceId) {
  const user = await findLoginUser(phone);
  if (await isTrusted(user._id, deviceId)) {
    user.lastLoginAt = new Date();
    await user.save();
    return { user, needCode: false };
  }
  const otp = await issueCode(phone, 'NEW_DEVICE');
  return { user: null, needCode: true, otp };
}

export async function completeLogin(phone, code) {
  const user = await findLoginUser(phone);
  await verifyCode(phone, 'NEW_DEVICE', code);
  user.lastLoginAt = new Date();
  await user.save();
  return user;
}

export async function resendCode(phone, purpose) {
  if (purpose === 'REGISTER') {
    const last = await VerificationCode.findOne({ phone, purpose: 'REGISTER' }).sort({ createdAt: -1 }).lean();
    if (!last?.registerData) throw badRequest('Cô chú vui lòng điền lại thông tin đăng ký.');
    if (await User.exists({ phone })) throw conflict('Số điện thoại này đã có tài khoản.', 'PHONE_TAKEN');
    return issueCode(phone, 'REGISTER', last.registerData);
  }
  await findLoginUser(phone);
  return issueCode(phone, 'NEW_DEVICE');
}

// ---------- Nội bộ: Tiếp tân, Admin ----------

export async function staffLogin(identifier, password) {
  const id = identifier.trim();
  const query = id.includes('@') ? { email: id.toLowerCase() } : { phone: id.replace(/[\s.\-]/g, '') };
  const user = await User.findOne(query).select('+passwordHash');
  const fail = unauthorized('Số điện thoại/email hoặc mật khẩu chưa đúng.');
  if (!user || !user.passwordHash || !STAFF_ROLES.includes(user.role)) throw fail;
  if (!(await bcrypt.compare(password, user.passwordHash))) throw fail;
  if (!user.isActive) throw forbidden('Tài khoản đang bị khoá. Vui lòng liên hệ quản trị viên.');
  user.lastLoginAt = new Date();
  await user.save();
  return user;
}

export async function changePassword(userId, currentPassword, newPassword) {
  const user = await User.findById(userId).select('+passwordHash');
  if (!user?.passwordHash) throw badRequest('Tài khoản này không dùng mật khẩu.');
  if (!(await bcrypt.compare(currentPassword, user.passwordHash))) {
    throw badRequest('Mật khẩu hiện tại chưa đúng.', { fields: { currentPassword: 'Mật khẩu hiện tại chưa đúng.' } });
  }
  if (currentPassword === newPassword) {
    throw badRequest('Mật khẩu mới phải khác mật khẩu hiện tại.', { fields: { newPassword: 'Mật khẩu mới phải khác mật khẩu hiện tại.' } });
  }
  user.passwordHash = await hashPassword(newPassword);
  user.mustChangePassword = false;
  await user.save();
  return user;
}

export const hashPassword = (password) => bcrypt.hash(password, 10);
