import crypto from 'node:crypto';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { config, rules } from '../config.js';
import { TrustedDevice, User, VerificationCode, STAFF_ROLES } from '../models/index.js';
import { badRequest, conflict, forbidden, notFound, tooMany, unauthorized } from '../utils/errors.js';
import { normalizePhone } from '../shared/phone.js';
import { exposeCodeForDev, firebaseVerifier, sendVerificationCode, usesFirebase } from './smsGateway.js';
import { devFixedEmailCode, exposeEmailCodeForDev, sendEmailCode } from './emailGateway.js';
import { createPatient } from './patient.service.js';

const hmac = (value) => crypto.createHmac('sha256', config.jwtSecret).update(value).digest('hex');
const hashCode = (phone, purpose, code) => hmac(`${phone}:${purpose}:${code}`);
export const hashDevice = (deviceId) => hmac(`device:${deviceId}`);
export const newDeviceId = () => crypto.randomBytes(32).toString('base64url');

function randomCode() {
  return String(crypto.randomInt(0, 10 ** rules.otp.length)).padStart(rules.otp.length, '0');
}

/**
 * Gửi mã xác nhận, chờ 60 giây giữa hai lần gửi cùng loại mã (BR.03).
 * Mã luôn gắn với số điện thoại của tài khoản; có `email` thì gửi mã tới email đó thay vì tin nhắn.
 * Giới hạn mỗi giờ tính riêng: tin nhắn 3 mã cho mỗi số điện thoại, email 5 mã cho mỗi địa chỉ email.
 */
export async function issueCode(phone, purpose, registerData = null, { email = null } = {}) {
  const now = Date.now();
  const since = { $gte: new Date(now - 3600_000) };
  const recent = await VerificationCode.find(email ? { email, createdAt: since } : { phone, email: null, createdAt: since })
    .sort({ createdAt: -1 })
    .lean();
  const maxPerHour = email ? rules.otp.emailMaxPerHour : rules.otp.maxPerHour;

  const lastSamePurpose = recent.find((c) => c.purpose === purpose);
  if (lastSamePurpose) {
    const waitSec = Math.ceil((lastSamePurpose.createdAt.getTime() + rules.otp.resendSeconds * 1000 - now) / 1000);
    if (waitSec > 0) {
      throw tooMany(`Cô chú vui lòng chờ ${waitSec} giây rồi bấm gửi lại mã.`, { retryAfter: waitSec });
    }
  }
  if (recent.length >= maxPerHour) {
    const oldest = recent[recent.length - 1].createdAt.getTime();
    const waitMin = Math.ceil((oldest + 3600_000 - now) / 60_000);
    throw tooMany(
      `${email ? 'Email' : 'Số điện thoại'} này đã nhận đủ ${maxPerHour} mã trong một giờ. Cô chú vui lòng thử lại sau khoảng ${waitMin} phút hoặc gọi phòng khám để được giúp.`,
      { retryAfter: waitMin * 60 },
    );
  }

  // Mã cũ chưa dùng của cùng mục đích hết hiệu lực khi có mã mới
  await VerificationCode.updateMany(
    { phone, purpose, usedAt: null, expiresAt: { $gt: new Date(now) } },
    { $set: { expiresAt: new Date(now) } },
  );

  // Firebase: trình duyệt tự gửi mã; ở đây chỉ ghi nhận lần xin mã (để giữ giới hạn và đối chiếu khi xác nhận)
  const firebase = !email && usesFirebase();
  const code = firebase ? null : (email && devFixedEmailCode()) || randomCode();
  const doc = await VerificationCode.create({
    phone,
    email,
    purpose,
    codeHash: firebase ? 'firebase' : hashCode(phone, purpose, code),
    registerData,
    expiresAt: new Date(now + rules.otp.ttlMinutes * 60_000),
    createdAt: new Date(now),
  });
  try {
    if (email) await sendEmailCode(email, code, purpose);
    else if (!firebase) await sendVerificationCode(phone, code, purpose);
  } catch (err) {
    // Gửi không được thì không tính lần xin mã này
    await VerificationCode.deleteOne({ _id: doc._id });
    throw err;
  }

  return {
    sent: true,
    provider: firebase ? 'firebase' : 'server',
    expiresInSeconds: rules.otp.ttlMinutes * 60,
    resendAfterSeconds: rules.otp.resendSeconds,
    ...((email ? exposeEmailCodeForDev() : exposeCodeForDev()) ? { devCode: code } : {}),
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

/**
 * Firebase: kiểm ID token trình duyệt nhận được sau khi nhập đúng mã. Token phải đúng số điện thoại,
 * vừa mới xác thực, và có lần xin mã tương ứng còn hạn, chưa dùng (mỗi lần xin mã chỉ dùng được một lần).
 */
async function verifyFirebase(phone, purpose, idToken) {
  if (!idToken) throw badRequest('Cô chú vui lòng nhập mã xác nhận.', { fields: { code: 'Cô chú vui lòng nhập mã xác nhận.' } }, 'CODE_REQUIRED');
  let info;
  try {
    info = await firebaseVerifier.verify(idToken);
  } catch {
    throw badRequest('Phòng khám chưa xác nhận được mã. Cô chú bấm "Gửi lại mã" rồi thử lại.', null, 'TOKEN_INVALID');
  }
  if (!info.phone || normalizePhone(info.phone) !== phone) {
    throw badRequest('Mã xác nhận không thuộc số điện thoại này.', null, 'PHONE_MISMATCH');
  }
  const now = new Date();
  if (!info.authTime || now - info.authTime > (rules.otp.ttlMinutes + 1) * 60_000) {
    throw badRequest('Mã xác nhận đã hết hạn. Cô chú bấm "Gửi lại mã" để nhận mã mới.', null, 'CODE_EXPIRED');
  }
  const used = await VerificationCode.findOneAndUpdate(
    { phone, purpose, usedAt: null, expiresAt: { $gt: now } },
    { $set: { usedAt: now } },
    { sort: { createdAt: -1 }, new: true },
  );
  if (!used) throw badRequest('Mã xác nhận đã hết hạn. Cô chú bấm "Gửi lại mã" để nhận mã mới.', null, 'CODE_EXPIRED');
  return used;
}

/** Bằng chứng giữ số điện thoại: mã 6 số (server tự gửi) hoặc ID token Firebase. */
function verifyProof(phone, purpose, { code, idToken } = {}) {
  if (usesFirebase()) return verifyFirebase(phone, purpose, idToken);
  if (!code) throw badRequest('Cô chú vui lòng nhập mã xác nhận.', { fields: { code: 'Mã xác nhận gồm 6 chữ số.' } }, 'CODE_REQUIRED');
  return verifyCode(phone, purpose, code);
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

export async function completeRegister(phone, proof) {
  const doc = await verifyProof(phone, 'REGISTER', proof);
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

export async function completeLogin(phone, proof) {
  const user = await findLoginUser(phone);
  await verifyProof(phone, 'NEW_DEVICE', proof);
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

// ---------- Nội bộ: quên mật khẩu (mã 6 số gửi qua email) ----------

const RESET_MINUTES = 10;
// Khoá riêng cho token đặt lại mật khẩu, để token này không dùng làm phiên đăng nhập được
const resetKey = () => hmac('reset-password');
// Dấu của mật khẩu hiện tại: đặt lại xong thì dấu đổi, token cũ hết dùng được
const passwordStamp = (user) => hmac(`pw:${user.passwordHash}`).slice(0, 24);
const resetExpired = () =>
  badRequest('Phiên đặt lại mật khẩu đã hết hạn. Cô chú làm lại từ bước nhập email.', null, 'RESET_EXPIRED');

async function activeStaffByEmail(email) {
  const user = await User.findOne({ email }).select('+passwordHash');
  return user && user.isActive && user.passwordHash && STAFF_ROLES.includes(user.role) ? user : null;
}

/** Gửi mã tới email. Không cho biết email có thuộc tài khoản nội bộ đang hoạt động hay không. */
export async function startPasswordReset(email) {
  const user = await activeStaffByEmail(email);
  if (!user) {
    return { sent: true, email, expiresInSeconds: rules.otp.ttlMinutes * 60, resendAfterSeconds: rules.otp.resendSeconds };
  }
  return { ...(await issueCode(user.phone, 'RESET_PASSWORD', null, { email })), email };
}

/** Mã đúng → token ngắn hạn để đặt mật khẩu mới. */
export async function verifyPasswordReset(email, code) {
  const user = await activeStaffByEmail(email);
  if (!user) throw badRequest('Mã xác nhận đã hết hạn. Cô chú bấm "Gửi lại mã" để nhận mã mới.', null, 'CODE_EXPIRED');
  await verifyCode(user.phone, 'RESET_PASSWORD', code);
  const resetToken = jwt.sign({ sub: user._id.toString(), pw: passwordStamp(user) }, resetKey(), { expiresIn: `${RESET_MINUTES}m` });
  return { resetToken, expiresInSeconds: RESET_MINUTES * 60 };
}

export async function resetPassword(resetToken, newPassword) {
  let payload;
  try {
    payload = jwt.verify(resetToken, resetKey());
  } catch {
    throw resetExpired();
  }
  const user = await User.findById(payload.sub).select('+passwordHash');
  if (!user || !user.isActive || !user.passwordHash || !STAFF_ROLES.includes(user.role) || payload.pw !== passwordStamp(user)) {
    throw resetExpired();
  }
  user.passwordHash = await hashPassword(newPassword);
  user.mustChangePassword = false;
  await user.save();
  return user;
}
