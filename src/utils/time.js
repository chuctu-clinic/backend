// Phòng khám làm việc theo giờ Việt Nam (UTC+7, không có giờ mùa hè).
const VN_OFFSET_MS = 7 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;

const pad = (n) => String(n).padStart(2, '0');

/** Đồng hồ của các quy tắc theo giờ (check-in trong giờ nhận bệnh). Kiểm thử thay now() để cố định thời điểm. */
export const clock = { now: () => new Date() };

/** Ngày hiện tại theo giờ Việt Nam, dạng YYYY-MM-DD. */
export function todayVN(now = new Date()) {
  return toDateStr(now);
}

/** Đổi một thời điểm thành ngày YYYY-MM-DD theo giờ Việt Nam. */
export function toDateStr(date) {
  const d = new Date(date.getTime() + VN_OFFSET_MS);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

export function isDateStr(s) {
  if (typeof s !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(s)) return false;
  const time = Date.parse(`${s}T00:00:00Z`);
  // Date.parse tự đẩy 30/02 thành 02/03: đối chiếu lại để loại ngày không có thật
  return !Number.isNaN(time) && new Date(time).toISOString().slice(0, 10) === s;
}

/** Số ngày từ a tới b (YYYY-MM-DD). */
export function diffDays(a, b) {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / DAY_MS);
}

export function addDays(dateStr, n) {
  const d = new Date(Date.parse(`${dateStr}T00:00:00Z`) + n * DAY_MS);
  return d.toISOString().slice(0, 10);
}

/** Giá trị lưu vào trường slotDate: nửa đêm UTC mang nhãn ngày khám. */
export function slotDateValue(dateStr) {
  return new Date(`${dateStr}T00:00:00Z`);
}

export function slotDateStr(value) {
  return value.toISOString().slice(0, 10);
}

/** Thời điểm bắt đầu khung giờ (giờ Việt Nam) thành Date. */
export function slotInstant(dateStr, hhmm) {
  return new Date(`${dateStr}T${hhmm}:00+07:00`);
}

/** Số phút trong ngày (giờ Việt Nam) của một thời điểm. */
export function minutesOfDayVN(date = new Date()) {
  const d = new Date(date.getTime() + VN_OFFSET_MS);
  return d.getUTCHours() * 60 + d.getUTCMinutes();
}

export function hhmmToMinutes(hhmm) {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
}

/** DDMM của ngày, dùng trong mã phiếu khám. */
export function ddmm(dateStr) {
  return dateStr.slice(8, 10) + dateStr.slice(5, 7);
}

export function yy(dateStr) {
  return dateStr.slice(2, 4);
}
