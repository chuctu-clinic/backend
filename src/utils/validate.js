import { z } from 'zod';
import { badRequest } from './errors.js';
import { isDateStr } from './time.js';
import { normalizePhone, phoneProblem } from '../shared/phone.js';

/** Kiểm tra dữ liệu theo schema zod; lỗi trả về theo từng trường để giao diện hiện ngay dưới ô nhập. */
export function parse(schema, data) {
  const result = schema.safeParse(data);
  if (!result.success) {
    const fields = {};
    for (const issue of result.error.issues) {
      const key = issue.path.join('.') || '_';
      if (!fields[key]) fields[key] = issue.message;
    }
    throw badRequest('Thông tin chưa hợp lệ, cô chú kiểm tra lại các ô được đánh dấu.', { fields }, 'VALIDATION');
  }
  return result.data;
}

export { normalizePhone };

export const phone = z
  .string({ required_error: 'Cô chú vui lòng nhập số điện thoại.' })
  .transform(normalizePhone)
  .superRefine((s, ctx) => {
    const problem = phoneProblem(s);
    if (problem) ctx.addIssue({ code: z.ZodIssueCode.custom, message: problem });
  });

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

export function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function paging(query, defaultLimit = 20) {
  const page = Math.max(1, Number.parseInt(query.page, 10) || 1);
  const limit = Math.min(100, Math.max(1, Number.parseInt(query.limit, 10) || defaultLimit));
  return { page, limit, skip: (page - 1) * limit };
}
