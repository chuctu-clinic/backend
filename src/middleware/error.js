import { config } from '../config.js';
import { AppError } from '../utils/errors.js';

export function notFoundHandler(req, res) {
  res.status(404).json({ error: { code: 'NOT_FOUND', message: 'Không tìm thấy đường dẫn.' } });
}

// eslint-disable-next-line no-unused-vars
export function errorHandler(err, req, res, next) {
  if (err instanceof AppError) {
    return res.status(err.status).json({ error: { code: err.code, message: err.message, ...(err.details || {}) } });
  }
  if (err?.type === 'entity.parse.failed') {
    return res.status(400).json({ error: { code: 'BAD_JSON', message: 'Dữ liệu gửi lên không hợp lệ.' } });
  }
  if (err?.name === 'CastError') {
    return res.status(404).json({ error: { code: 'NOT_FOUND', message: 'Không tìm thấy dữ liệu.' } });
  }
  if (err?.code === 11000) {
    return res.status(409).json({ error: { code: 'DUPLICATE', message: 'Dữ liệu đã tồn tại.' } });
  }
  if (!config.isTest) console.error(err);
  res.status(500).json({ error: { code: 'SERVER_ERROR', message: 'Hệ thống đang bận, cô chú vui lòng thử lại sau.' } });
}
