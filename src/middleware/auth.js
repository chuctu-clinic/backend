import jwt from 'jsonwebtoken';
import { config, rules } from '../config.js';
import { User, STAFF_ROLES } from '../models/index.js';
import { forbidden, unauthorized } from '../utils/errors.js';

// Trang người dùng và khu nội bộ giữ hai phiên riêng: đăng nhập hay đăng xuất bên này không làm bên kia thoát
export const SESSION_COOKIE = 'ct_session';
export const STAFF_COOKIE = 'ct_staff';
export const DEVICE_COOKIE = 'ct_device';
const COOKIE_OF = { users: SESSION_COOKIE, staff: STAFF_COOKIE };

/**
 * Yêu cầu thuộc phiên nào: API nội bộ luôn là "staff", API tài khoản người dùng luôn là "users";
 * các API dùng chung (đăng nhập, đăng xuất, /auth/me) theo header X-App mà mỗi ứng dụng gửi kèm.
 */
export function appOf(req) {
  const url = req.originalUrl || '';
  if (url.startsWith('/api/staff') || url.startsWith('/api/admin')) return 'staff';
  if (url.startsWith('/api/me')) return 'users';
  const app = req.get('x-app');
  return COOKIE_OF[app] ? app : null;
}

const cookieBase = {
  httpOnly: true,
  sameSite: config.isProd ? 'none' : 'lax',
  secure: config.isProd,
  path: '/',
};

export function setSession(res, user, { remember = true, app = 'users' } = {}) {
  const token = jwt.sign({ sub: user._id.toString(), role: user.role }, config.jwtSecret, {
    expiresIn: `${rules.session.days}d`,
  });
  res.cookie(COOKIE_OF[app], token, {
    ...cookieBase,
    // Không chọn "giữ đăng nhập" thì phiên kết thúc khi đóng trình duyệt
    ...(remember ? { maxAge: rules.session.days * 24 * 3600 * 1000 } : {}),
  });
}

/** Xoá phiên của một bên; không rõ bên nào thì xoá cả hai. */
export function clearSession(res, app = null) {
  for (const name of app ? [COOKIE_OF[app]] : Object.values(COOKIE_OF)) res.clearCookie(name, cookieBase);
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
  let app = appOf(req);
  // Không rõ bên nào (ví dụ gọi thẳng API): dùng phiên nào đang có
  if (!app) app = req.cookies?.[SESSION_COOKIE] ? 'users' : 'staff';
  let token = req.cookies?.[COOKIE_OF[app]];
  // Phiên nội bộ cũ còn nằm trong cookie chung: chuyển sang cookie nội bộ, khỏi phải đăng nhập lại
  if (!token && app === 'staff' && req.cookies?.[SESSION_COOKIE]) {
    try {
      const old = jwt.verify(req.cookies[SESSION_COOKIE], config.jwtSecret);
      if (STAFF_ROLES.includes(old.role)) {
        token = req.cookies[SESSION_COOKIE];
        res.cookie(STAFF_COOKIE, token, { ...cookieBase, maxAge: rules.session.days * 24 * 3600 * 1000 });
        res.clearCookie(SESSION_COOKIE, cookieBase);
      }
    } catch {
      /* cookie cũ hỏng thì bỏ qua */
    }
  }
  if (!token) return next();
  try {
    const payload = jwt.verify(token, config.jwtSecret);
    const user = await User.findById(payload.sub);
    if (user && user.isActive) req.user = user;
    else clearSession(res, app);
  } catch {
    clearSession(res, app);
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
