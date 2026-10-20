import type { NextFunction, Request, RequestHandler, Response } from 'express';

export type ErrorDetails = Record<string, unknown> | null | undefined;

export class AppError extends Error {
  status: number;
  code: string;
  details: ErrorDetails;

  constructor(status: number, code: string, message: string, details?: ErrorDetails) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export const badRequest = (message: string, details?: ErrorDetails, code = 'BAD_REQUEST') => new AppError(400, code, message, details);
export const unauthorized = (message = 'Cô chú vui lòng đăng nhập để tiếp tục.') => new AppError(401, 'UNAUTHORIZED', message);
export const forbidden = (message = 'Tài khoản không có quyền thực hiện thao tác này.') => new AppError(403, 'FORBIDDEN', message);
export const notFound = (message = 'Không tìm thấy dữ liệu.') => new AppError(404, 'NOT_FOUND', message);
export const conflict = (message: string, code = 'CONFLICT', details?: ErrorDetails) => new AppError(409, code, message, details);
export const tooMany = (message: string, details?: ErrorDetails) => new AppError(429, 'TOO_MANY_REQUESTS', message, details);

/** Bọc handler async để lỗi chuyển tới middleware xử lý lỗi. */
export const ah =
  (fn: (req: Request, res: Response, next: NextFunction) => Promise<unknown>): RequestHandler =>
  (req, res, next) =>
    Promise.resolve(fn(req, res, next)).catch(next);

/** Mã lỗi của MongoDB (ví dụ 11000: trùng khoá duy nhất), nếu có. */
export const mongoErrorCode = (err: unknown) =>
  typeof err === 'object' && err !== null && 'code' in err ? err.code : undefined;
