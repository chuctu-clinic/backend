// BẢN CHÉP: file này có ở cả repo backend (src/shared/) và repo frontend (shared/src/). Sửa bên nào thì chép sang bên kia.
// Số điện thoại di động Việt Nam: dùng chung giữa server và client để hai bên báo cùng một lỗi

/** Chuẩn hoá về dạng 0xxxxxxxxx: bỏ khoảng trắng, dấu chấm, gạch; "+84…" hoặc "84…" (11 số) đổi thành "0…". */
export function normalizePhone(raw) {
  if (typeof raw !== 'string') return raw;
  let s = raw.replace(/[\s.\-()]/g, '');
  if (s.startsWith('+84')) s = '0' + s.slice(3);
  else if (s.startsWith('84') && s.length === 11) s = '0' + s.slice(2);
  return s;
}

const LENGTH_NOTE = 'Số di động gồm 10 chữ số.';

/**
 * Lý do số điện thoại không hợp lệ, null nếu hợp lệ.
 * Hợp lệ: 10 chữ số bắt đầu bằng 03, 05, 07, 08, 09; nhận cả dạng +84 (+84912345678).
 */
export function phoneProblem(raw, { required = true } = {}) {
  const s = normalizePhone(raw ?? '');
  if (!s) return required ? 'Cô chú vui lòng nhập số điện thoại.' : null;
  if (!/^\d+$/.test(s)) return 'Số điện thoại chỉ gồm chữ số.';
  // Phần sau số 0 đầu (hoặc sau +84) phải đủ 9 chữ số
  const rest = s.startsWith('0') ? s.slice(1) : s;
  if (rest.length < 9) return `Số điện thoại còn thiếu ${9 - rest.length} chữ số. ${LENGTH_NOTE}`;
  if (rest.length > 9) return `Số điện thoại bị thừa ${rest.length - 9} chữ số. ${LENGTH_NOTE}`;
  if (!/^[35789]/.test(rest)) return `Đầu số 0${rest[0]} không phải đầu số di động. Số di động bắt đầu bằng 03, 05, 07, 08 hoặc 09.`;
  return null;
}

/**
 * Chữ số trong ô có sẵn tiền tố +84. Dán cả "+84912345678" thì bỏ phần 84.
 * Cho gõ dư vài số để báo "thừa chữ số" thay vì lặng lẽ cắt bớt.
 */
export function phoneDigits(v) {
  const raw = String(v ?? '');
  let d = raw.replace(/\D/g, '');
  if (/^\s*\+84/.test(raw) || (d.startsWith('84') && d.length === 11)) d = d.slice(2);
  return d.slice(0, 12);
}

/** Chữ số trong ô → số gửi lên server: 9 số sau +84 thì thêm số 0 ở đầu. */
export const toPhone = (digits) => (digits.length === 9 ? `0${digits}` : digits);

/** Lỗi của ô số điện thoại (có tiền tố +84), cùng quy tắc với server. */
export const phoneInputError = (digits, { required = true } = {}) => phoneProblem(toPhone(digits), { required });
