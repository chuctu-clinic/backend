import type { UserDoc } from '../models/User.ts';

declare global {
  namespace Express {
    interface Request {
      /** Tài khoản đang đăng nhập, do middleware loadUser gắn vào. */
      user: UserDoc | null;
    }
  }
}
