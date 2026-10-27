import jwt from 'jsonwebtoken';
import { config, rules } from '../config.js';
import { User, STAFF_ROLES } from '../models/index.js';
import { conflict, forbidden, unauthorized } from '../utils/errors.js';

// Một phiên đăng nhập cho cả trang người dùng và khu nội bộ; quyền theo vai trò của tài khoản
export const SESSION_COOKIE = 'ct_session';
// Cookie phiên nội bộ của bản trước (hai phiên riêng): còn thì chuyển sang ct_session
const LEGACY_STAFF_COOKIE = 'ct_staff';
export const DEVICE_COOKIE = 'ct_device';

const cookieBase = {
  httpOnly: true,
  sameSite: config.isProd ? 'none' : 'lax',
  secure: config.isProd,
  path: '/',
};
const sessionMaxAge = () => rules.session.days * 24 * 3600 * 1000;

export function setSession(res, user, { remember = true } = {}) {
  const token = jwt.sign({ sub: user._id.toString(), role: user.role }, config.jwtSecret, {
    expiresIn: `${rules.session.days}d`,
  });
  res.cookie(SESSION_COOKIE, token, {
    ...cookieBase,
    // Không chọn "giữ đăng nhập" thì phiên kết thúc khi đóng trình duyệt
    ...(remember ? { maxAge: sessionMaxAge() } : {}),
  });
}

/** Xoá phiên, kể cả cookie phiên nội bộ cũ. */
export function clearSession(res) {
  for (const name of [SESSION_COOKIE, LEGACY_STAFF_COOKIE]) res.clearCookie(name, cookieBase);
}

export function setDeviceCookie(res, deviceId) {
  res.cookie(DEVICE_COOKIE, deviceId, { ...cookieBase, maxAge: 365 * 24 * 3600 * 1000 });
}

/**
 * Đọc phiên đăng nhập nếu có. Luôn tải lại tài khoản từ CSDL để tài khoản bị khoá hoặc
 * đổi vai trò được áp dụng ngay ở yêu cầu kế tiếp (BR.32, BR.33).
 */
export async function loadUser(req, res, next) {
  try {
    await readSession(req, res);
  } catch (err) {
    return next(err);
  }
  // Mỗi tab gửi kèm tài khoản nó đang hiển thị. Cookie là chung cho mọi tab của trình duyệt, nên nếu
  // tab khác vừa đăng nhập tài khoản khác (hoặc đăng xuất) thì từ chối, tránh ghi thao tác nhầm tên người khác.
  // Các API /auth (xem phiên, đăng nhập, đăng xuất) vẫn cho qua để tab tự cập nhật lại.
  const expected = req.get('x-session-user');
  if (expected && !(req.originalUrl || '').startsWith('/api/auth/') && expected !== String(req.user?._id ?? '')) {
    return next(conflict('Tài khoản trên trình duyệt này vừa thay đổi ở một thẻ khác. Trang sẽ tải lại theo tài khoản hiện tại.', 'SESSION_CHANGED'));
  }
  next();
}

async function readSession(req, res) {
  req.user = null;
  let token = req.cookies?.[SESSION_COOKIE];
  const legacy = req.cookies?.[LEGACY_STAFF_COOKIE];
  if (token && legacy) res.clearCookie(LEGACY_STAFF_COOKIE, cookieBase);
  // Phiên nội bộ của bản trước: chuyển sang cookie chung, khỏi phải đăng nhập lại
  if (!token && legacy) {
    token = legacy;
    res.cookie(SESSION_COOKIE, token, { ...cookieBase, maxAge: sessionMaxAge() });
    res.clearCookie(LEGACY_STAFF_COOKIE, cookieBase);
  }
  if (!token) return;
  try {
    const payload = jwt.verify(token, config.jwtSecret);
    const user = await User.findById(payload.sub);
    if (user && user.isActive) req.user = user;
    else clearSession(res);
  } catch {
    clearSession(res);
  }
}

export function requireAuth(req, res, next) {
  if (!req.user) return next(unauthorized());
  next();
}

export function requireRole(...roles) {
  return (req, res, next) => {
    if (!req.user) return next(unauthorized());
    if (!roles.includes(req.user.role)) return next(forbidden());
    // Tài khoản nội bộ dùng mật khẩu tạm phải đổi mật khẩu trước khi làm việc
    if (req.user.mustChangePassword && STAFF_ROLES.includes(req.user.role)) {
      return next(forbidden('Cô chú cần đổi mật khẩu tạm trước khi tiếp tục.'));
    }
    next();
  };
}

export const requireUser = requireRole('USER');
export const requireStaff = requireRole('RECEPTIONIST', 'ADMIN');
export const requireAdmin = requireRole('ADMIN');
