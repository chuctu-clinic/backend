// Hằng số dùng chung với frontend. Bản ở repo frontend: shared/src/constants.ts.
// Sửa ở đây thì sửa cả bên đó để nhãn và trạng thái hai bên khớp nhau.

/** Các khoá của một object dạng tuple, dùng được cho z.enum và enum của Mongoose. */
export const keysOf = <K extends string>(o: Record<K, unknown>) => Object.keys(o) as [K, ...K[]];

export const ROLES = ['USER', 'RECEPTIONIST', 'ADMIN'] as const;
export type Role = (typeof ROLES)[number];
export const STAFF_ROLES: readonly Role[] = ['RECEPTIONIST', 'ADMIN'];
export const ROLE_LABELS: Record<Role, string> = { USER: 'Người dùng', RECEPTIONIST: 'Tiếp tân', ADMIN: 'Quản trị viên' };

export const GENDERS = { NAM: 'Nam', NU: 'Nữ', KHAC: 'Khác' };
export type Gender = keyof typeof GENDERS;

export const RELATIONSHIPS = {
  BAN_THAN: 'Bản thân',
  VO_CHONG: 'Vợ / chồng',
  CON: 'Con',
  CHA_ME: 'Cha / mẹ',
  ONG_BA: 'Ông / bà',
  ANH_CHI_EM: 'Anh / chị / em',
  KHAC: 'Khác',
};
export type Relationship = keyof typeof RELATIONSHIPS;

export const SERVICE_TYPES = {
  CHUA_RO: 'Chưa rõ — nhờ bác sĩ khám và chỉ định',
  CHAM_CUU: 'Châm cứu',
  VAT_LY_TRI_LIEU: 'Vật lý trị liệu — phục hồi chức năng',
  XOA_BOP_BAM_HUYET: 'Xoa bóp — bấm huyệt',
  BOC_THUOC: 'Bốc thuốc — thuốc Nam, thuốc Bắc',
};
export type ServiceType = keyof typeof SERVICE_TYPES;

// Trạng thái phiếu khám. Người dùng chỉ thấy Đã đặt lịch, Đã hoàn thành, Đã huỷ (BR.17).
export const APPOINTMENT_STATUSES = ['BOOKED', 'CHECKED_IN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as const;
export type AppointmentStatus = (typeof APPOINTMENT_STATUSES)[number];
export const ACTIVE_STATUSES: readonly AppointmentStatus[] = ['BOOKED', 'CHECKED_IN', 'IN_PROGRESS'];
export const STATUS_LABELS: Record<AppointmentStatus, string> = {
  BOOKED: 'Chưa đến',
  CHECKED_IN: 'Đã check-in',
  IN_PROGRESS: 'Đã vào khám',
  COMPLETED: 'Đã hoàn thành',
  CANCELLED: 'Đã huỷ',
};
export type UserStatus = 'BOOKED' | 'COMPLETED' | 'CANCELLED';
export const USER_STATUS_LABELS: Record<UserStatus, string> = { BOOKED: 'Đã đặt lịch', COMPLETED: 'Đã hoàn thành', CANCELLED: 'Đã huỷ' };
