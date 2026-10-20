import type { CookieOptions, NextFunction, Request, Response } from 'express';
import jwt from 'jsonwebtoken';
import { config, rules } from '../config.ts';
import { User, STAFF_ROLES, type UserDoc } from '../models/index.ts';
import type { Role } from '../shared/constants.ts';
import { forbidden, unauthorized } from '../utils/errors.ts';

export const SESSION_COOKIE = 'ct_session';
export const DEVICE_COOKIE = 'ct_device';

const cookieBase: CookieOptions = {
  httpOnly: true,
  sameSite: config.isProd ? 'none' : 'lax',
  secure: config.isProd,
  path: '/',
};

export function setSession(res: Response, user: UserDoc, { remember = true } = {}) {
  const token = jwt.sign({ sub: user._id.toString(), role: user.role }, config.jwtSecret, {
    expiresIn: `${rules.session.days}d`,
  });
  res.cookie(SESSION_COOKIE, token, {
    ...cookieBase,
    // Không chọn "giữ đăng nhập" thì phiên kết thúc khi đóng trình duyệt
    ...(remember ? { maxAge: rules.session.days * 24 * 3600 * 1000 } : {}),
  });
}

export function clearSession(res: Response) {
  res.clearCookie(SESSION_COOKIE, cookieBase);
}

export function setDeviceCookie(res: Response, deviceId: string) {
  res.cookie(DEVICE_COOKIE, deviceId, { ...cookieBase, maxAge: 365 * 24 * 3600 * 1000 });
}

/** Cookie dạng chuỗi trong yêu cầu, nếu có. */
export function cookie(req: Request, name: string): string | undefined {
  const value: unknown = req.cookies?.[name];
  return typeof value === 'string' ? value : undefined;
}

/**
 * Đọc phiên đăng nhập nếu có. Luôn tải lại tài khoản từ CSDL để tài khoản bị khoá hoặc
 * đổi vai trò được áp dụng ngay ở yêu cầu kế tiếp (BR.32, BR.33).
 */
export async function loadUser(req: Request, res: Response, next: NextFunction) {
  req.user = null;
  const token = cookie(req, SESSION_COOKIE);
  if (!token) return next();
  try {
    const payload = jwt.verify(token, config.jwtSecret);
    const user = typeof payload === 'object' ? await User.findById(payload.sub) : null;
    if (user && user.isActive) req.user = user;
    else clearSession(res);
  } catch {
    clearSession(res);
  }
  next();
}

/** Tài khoản đang đăng nhập; dùng sau requireAuth/requireRole nên luôn có. */
export function me(req: Request): UserDoc {
  if (!req.user) throw unauthorized();
  return req.user;
}

export function requireAuth(req: Request, res: Response, next: NextFunction) {
  if (!req.user) return next(unauthorized());
  next();
}

export function requireRole(...roles: Role[]) {
  return (req: Request, res: Response, next: NextFunction) => {
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
