import jwt from 'jsonwebtoken';
import { config, rules } from '../config.js';
import { User, STAFF_ROLES } from '../models/index.js';
import { forbidden, unauthorized } from '../utils/errors.js';

export const SESSION_COOKIE = 'ct_session';
export const DEVICE_COOKIE = 'ct_device';

const cookieBase = {
  httpOnly: true,
  sameSite: config.isProd ? 'none' : 'lax',
  secure: config.isProd,
  path: '/',
};

export function setSession(res, user, { remember = true } = {}) {
  const token = jwt.sign({ sub: user._id.toString(), role: user.role }, config.jwtSecret, {
    expiresIn: `${rules.session.days}d`,
  });
  res.cookie(SESSION_COOKIE, token, {
    ...cookieBase,
    // Không chọn "giữ đăng nhập" thì phiên kết thúc khi đóng trình duyệt
    ...(remember ? { maxAge: rules.session.days * 24 * 3600 * 1000 } : {}),
  });
}

export function clearSession(res) {
  res.clearCookie(SESSION_COOKIE, cookieBase);
}

export function setDeviceCookie(res, deviceId) {
  res.cookie(DEVICE_COOKIE, deviceId, { ...cookieBase, maxAge: 365 * 24 * 3600 * 1000 });
}

/**
 * Đọc phiên đăng nhập nếu có. Luôn tải lại tài khoản từ CSDL để tài khoản bị khoá hoặc
 * đổi vai trò được áp dụng ngay ở yêu cầu kế tiếp (BR.32, BR.33).
 */
export async function loadUser(req, res, next) {
  req.user = null;
  const token = req.cookies?.[SESSION_COOKIE];
  if (!token) return next();
  try {
    const payload = jwt.verify(token, config.jwtSecret);
    const user = await User.findById(payload.sub);
    if (user && user.isActive) req.user = user;
    else clearSession(res);
  } catch {
    clearSession(res);
  }
  next();
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
