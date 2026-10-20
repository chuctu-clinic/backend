import type { NextFunction, Request, Response } from 'express';
import { config } from '../config.ts';
import { AppError } from '../utils/errors.ts';

export function notFoundHandler(req: Request, res: Response) {
  res.status(404).json({ error: { code: 'NOT_FOUND', message: 'Không tìm thấy đường dẫn.' } });
}

// eslint-disable-next-line no-unused-vars
export function errorHandler(error: unknown, req: Request, res: Response, next: NextFunction) {
  const err = error as { type?: string; name?: string; code?: unknown } | null | undefined;
  if (error instanceof AppError) {
    return res.status(error.status).json({ error: { code: error.code, message: error.message, ...(error.details || {}) } });
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
  if (!config.isTest) console.error(error);
  res.status(500).json({ error: { code: 'SERVER_ERROR', message: 'Hệ thống đang bận, cô chú vui lòng thử lại sau.' } });
}
