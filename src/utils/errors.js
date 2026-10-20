export class AppError extends Error {
  constructor(status, code, message, details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export const badRequest = (message, details, code = 'BAD_REQUEST') => new AppError(400, code, message, details);
export const unauthorized = (message = 'Cô chú vui lòng đăng nhập để tiếp tục.') => new AppError(401, 'UNAUTHORIZED', message);
export const forbidden = (message = 'Tài khoản không có quyền thực hiện thao tác này.') => new AppError(403, 'FORBIDDEN', message);
export const notFound = (message = 'Không tìm thấy dữ liệu.') => new AppError(404, 'NOT_FOUND', message);
export const conflict = (message, code = 'CONFLICT', details) => new AppError(409, code, message, details);
export const tooMany = (message, details) => new AppError(429, 'TOO_MANY_REQUESTS', message, details);

/** Bọc handler async để lỗi chuyển tới middleware xử lý lỗi. */
export const ah = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
