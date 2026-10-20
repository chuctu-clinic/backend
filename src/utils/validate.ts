import { z } from 'zod';
import { badRequest } from './errors.ts';
import { isDateStr } from './time.ts';

/** Kiểm tra dữ liệu theo schema zod; lỗi trả về theo từng trường để giao diện hiện ngay dưới ô nhập. */
export function parse<S extends z.ZodTypeAny>(schema: S, data: unknown): z.output<S> {
  const result = schema.safeParse(data);
  if (!result.success) {
    const fields: Record<string, string> = {};
    for (const issue of result.error.issues) {
      const key = issue.path.join('.') || '_';
      if (!fields[key]) fields[key] = issue.message;
    }
    throw badRequest('Thông tin chưa hợp lệ, cô chú kiểm tra lại các ô được đánh dấu.', { fields }, 'VALIDATION');
  }
  return result.data;
}

/** Chuẩn hoá số điện thoại Việt Nam về dạng 0xxxxxxxxx. */
export function normalizePhone(raw: string) {
  let s = raw.replace(/[\s.\-()]/g, '');
  if (s.startsWith('+84')) s = '0' + s.slice(3);
  else if (s.startsWith('84') && s.length === 11) s = '0' + s.slice(2);
  return s;
}

export const phone = z
  .string({ required_error: 'Cô chú vui lòng nhập số điện thoại.' })
  .transform(normalizePhone)
  .refine((s) => /^0[35789]\d{8}$/.test(s), 'Số điện thoại chưa đúng định dạng (10 số, bắt đầu bằng 0).');

export const fullName = z
  .string({ required_error: 'Cô chú vui lòng nhập họ và tên.' })
  .trim()
  .min(2, 'Cô chú vui lòng nhập họ và tên.')
  .max(100, 'Họ và tên quá dài.');

export const dateStr = z.string().refine(isDateStr, 'Ngày chưa đúng định dạng.');

export const gender = z.enum(['NAM', 'NU', 'KHAC'], { errorMap: () => ({ message: 'Cô chú vui lòng chọn giới tính.' }) });

export const optionalText = (max = 300) =>
  z
    .string()
    .trim()
    .max(max, `Tối đa ${max} ký tự.`)
    .optional()
    .nullable()
    .transform((v) => (v ? v : null));

export const objectId = z.string().regex(/^[a-f\d]{24}$/i, 'Mã không hợp lệ.');

export function escapeRegex(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Giá trị chuỗi của một tham số trên URL (?q=...); không có hoặc bị lặp thì trả về undefined. */
export function str(v: unknown) {
  return typeof v === 'string' ? v : undefined;
}

export interface Paging {
  skip: number;
  limit: number;
}

export function paging(query: Record<string, unknown>, defaultLimit = 20) {
  const page = Math.max(1, Number.parseInt(str(query.page) ?? '', 10) || 1);
  const limit = Math.min(100, Math.max(1, Number.parseInt(str(query.limit) ?? '', 10) || defaultLimit));
  return { page, limit, skip: (page - 1) * limit };
}
