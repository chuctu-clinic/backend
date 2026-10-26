import { z } from 'zod';
import { rules } from '../config.js';
import { RELATIONSHIPS, SERVICE_TYPES } from '../models/index.js';
import { todayVN } from '../utils/time.js';
import { dateStr, fullName, gender, objectId, optionalText, phone } from '../utils/validate.js';

const pastDate = dateStr.refine((s) => s < todayVN(), 'Ngày sinh phải trước hôm nay.');
const optionalPhone = z
  .union([phone, z.literal(''), z.null()])
  .optional()
  .transform((v) => (v ? v : null));

export const serviceType = z.enum(Object.keys(SERVICE_TYPES), {
  errorMap: () => ({ message: 'Cô chú vui lòng chọn nội dung khám.' }),
});
export const slotStart = z.enum(rules.booking.slots, { errorMap: () => ({ message: 'Cô chú vui lòng chọn giờ khám.' }) });
const relationship = z.enum(RELATIONSHIPS, { errorMap: () => ({ message: 'Cô chú vui lòng chọn quan hệ với người đi khám.' }) });

const optionalNationalId = z
  .union([z.string().trim().regex(/^\d{9}$|^\d{12}$/, 'Số căn cước gồm 12 chữ số (hoặc 9 số với CMND cũ).'), z.literal(''), z.null()])
  .optional()
  .transform((v) => (v ? v : null));
const optionalEmail = z
  .union([z.string().trim().email('Email chưa đúng định dạng.').max(200), z.literal(''), z.null()])
  .optional()
  .transform((v) => (v ? v.toLowerCase() : null));

const thisYear = Number(todayVN().slice(0, 4));
const birthYear = z.coerce
  .number({ invalid_type_error: 'Vui lòng chọn năm sinh.' })
  .int('Năm sinh chưa đúng.')
  .min(1900, 'Năm sinh chưa đúng.')
  .max(thisYear, 'Năm sinh chưa đúng.');

/** Hồ sơ khám do người dùng tự lập cho mình hoặc người thân: cần ngày sinh, hoặc ít nhất năm sinh. */
const profileFields = z.object({
  fullName,
  phone: optionalPhone,
  dob: pastDate.optional().nullable(),
  birthYear: birthYear.optional().nullable(),
  gender,
  relationship,
  address: optionalText(300),
  nationalId: optionalNationalId,
  email: optionalEmail,
});
export const profileSchema = profileFields.refine((d) => d.dob || d.birthYear, {
  message: 'Cô chú vui lòng chọn ngày sinh hoặc năm sinh.',
  path: ['dob'],
});
export const profileUpdateSchema = profileFields.partial();

// Ngày sinh, giới tính, địa chỉ là bắt buộc: gửi lên thì phải có giá trị, không xoá trắng được
export const accountUpdateSchema = z.object({
  fullName: fullName.optional(),
  dob: z.custom((v) => typeof v === 'string' && v !== '', 'Vui lòng chọn đủ ngày, tháng, năm sinh.').pipe(pastDate).optional(),
  gender: gender.optional(),
  address: z
    .string({ invalid_type_error: 'Vui lòng nhập địa chỉ.' })
    .trim()
    .min(1, 'Vui lòng nhập địa chỉ.')
    .max(300, 'Tối đa 300 ký tự.')
    .optional(),
});

export const bookingSchema = z.object({
  patientId: objectId,
  serviceType,
  slotDate: dateStr,
  slotStart,
  rebookFromId: objectId.optional().nullable(),
});

/** Tiếp tân tạo hồ sơ: bắt buộc họ tên, năm sinh, số điện thoại (BR.25). */
export const staffPatientSchema = z.object({
  fullName,
  phone,
  birthYear,
  dob: pastDate.optional().nullable(),
  gender: gender.optional().nullable(),
  address: optionalText(300),
});
export const staffPatientUpdateSchema = staffPatientSchema.partial();

const note = optionalText(2000);
export const recordSchema = z.object({
  visitDate: dateStr.optional(),
  diagnosis: note,
  pulse: optionalText(50),
  bloodPressure: optionalText(50),
  treatmentNotes: z.object({ acupuncture: note, acupressure: note, other: note }).partial().optional(),
  medicalHistory: note,
  followUpDate: dateStr.optional().nullable(),
  doctorName: optionalText(100),
});
