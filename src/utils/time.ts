// Phòng khám làm việc theo giờ Việt Nam (UTC+7, không có giờ mùa hè).
const VN_OFFSET_MS = 7 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;

const pad = (n: number) => String(n).padStart(2, '0');

/** Ngày hiện tại theo giờ Việt Nam, dạng YYYY-MM-DD. */
export function todayVN(now = new Date()) {
  return toDateStr(now);
}

/** Đổi một thời điểm thành ngày YYYY-MM-DD theo giờ Việt Nam. */
export function toDateStr(date: Date) {
  const d = new Date(date.getTime() + VN_OFFSET_MS);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

export function isDateStr(s: unknown): s is string {
  return typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(`${s}T00:00:00Z`));
}

/** Số ngày từ a tới b (YYYY-MM-DD). */
export function diffDays(a: string, b: string) {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / DAY_MS);
}

export function addDays(dateStr: string, n: number) {
  const d = new Date(Date.parse(`${dateStr}T00:00:00Z`) + n * DAY_MS);
  return d.toISOString().slice(0, 10);
}

/** Giá trị lưu vào trường slotDate: nửa đêm UTC mang nhãn ngày khám. */
export function slotDateValue(dateStr: string) {
  return new Date(`${dateStr}T00:00:00Z`);
}

export function slotDateStr(value: Date) {
  return value.toISOString().slice(0, 10);
}

/** Ngày YYYY-MM-DD thành Date lúc nửa đêm UTC; trống thì null. */
export function toDate(dateStr: string | null | undefined) {
  return dateStr ? new Date(`${dateStr}T00:00:00Z`) : null;
}

/** Thời điểm bắt đầu khung giờ (giờ Việt Nam) thành Date. */
export function slotInstant(dateStr: string, hhmm: string) {
  return new Date(`${dateStr}T${hhmm}:00+07:00`);
}

/** Số phút trong ngày (giờ Việt Nam) của một thời điểm. */
export function minutesOfDayVN(date = new Date()) {
  const d = new Date(date.getTime() + VN_OFFSET_MS);
  return d.getUTCHours() * 60 + d.getUTCMinutes();
}

export function hhmmToMinutes(hhmm: string) {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
}

/** DDMM của ngày, dùng trong mã phiếu khám. */
export function ddmm(dateStr: string) {
  return dateStr.slice(8, 10) + dateStr.slice(5, 7);
}

export function yy(dateStr: string) {
  return dateStr.slice(2, 4);
}
