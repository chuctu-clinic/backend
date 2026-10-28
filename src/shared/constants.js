// BẢN CHÉP: file này có ở cả repo backend (src/shared/) và repo frontend (shared/src/). Sửa bên nào thì chép sang bên kia.
// Hằng số dùng chung giữa server và hai ứng dụng client.
// File này chỉ là JavaScript thuần (không React, không import.meta.env) để Node.js nạp được.

export const ROLES = ['USER', 'RECEPTIONIST', 'ADMIN'];
export const STAFF_ROLES = ['RECEPTIONIST', 'ADMIN'];
export const ROLE_LABELS = { USER: 'Người dùng', RECEPTIONIST: 'Tiếp tân', ADMIN: 'Quản trị viên' };

export const GENDERS = { NAM: 'Nam', NU: 'Nữ', KHAC: 'Khác' };

export const RELATIONSHIPS = {
  BAN_THAN: 'Bản thân',
  VO_CHONG: 'Vợ / chồng',
  CON: 'Con',
  CHA_ME: 'Cha / mẹ',
  ONG_BA: 'Ông / bà',
  ANH_CHI_EM: 'Anh / chị / em',
  KHAC: 'Khác',
};

export const SERVICE_TYPES = {
  CHUA_RO: 'Chưa rõ — nhờ bác sĩ khám và chỉ định',
  CHAM_CUU: 'Châm cứu',
  VAT_LY_TRI_LIEU: 'Vật lý trị liệu — phục hồi chức năng',
  XOA_BOP_BAM_HUYET: 'Xoa bóp — bấm huyệt',
  BOC_THUOC: 'Bốc thuốc — thuốc Nam, thuốc Bắc',
};

// Trạng thái phiếu khám. Người dùng chỉ thấy Đã đặt lịch, Đã hoàn thành, Đã huỷ (BR.17).
export const APPOINTMENT_STATUSES = ['BOOKED', 'CHECKED_IN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];
export const ACTIVE_STATUSES = ['BOOKED', 'CHECKED_IN', 'IN_PROGRESS'];

// Giờ nhận bệnh (giờ Việt Nam), tất cả các ngày: chỉ check-in trong khoảng này, từ 16:30 là đóng cửa.
// Từ noShowCancel, phiếu trong ngày còn Chưa đến tự chuyển sang Đã huỷ.
export const RECEIVING_HOURS = { open: '08:00', close: '16:30', label: '8:00 – 16:30', noShowCancel: '17:00' };
export const STATUS_LABELS = {
  BOOKED: 'Chưa đến',
  CHECKED_IN: 'Đã check-in',
  IN_PROGRESS: 'Đã vào khám',
  COMPLETED: 'Đã hoàn thành',
  CANCELLED: 'Đã huỷ',
};
export const USER_STATUS_LABELS = { BOOKED: 'Đã đặt lịch', COMPLETED: 'Đã hoàn thành', CANCELLED: 'Đã huỷ' };
