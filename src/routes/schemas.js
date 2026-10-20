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

/** Hồ sơ khám do người dùng tự lập cho mình hoặc người thân. */
export const profileSchema = z.object({
  fullName,
  phone: optionalPhone,
  dob: pastDate,
  gender,
  relationship,
  address: optionalText(300),
});
export const profileUpdateSchema = profileSchema.partial();

export const accountUpdateSchema = z.object({
  fullName: fullName.optional(),
  dob: pastDate.optional().nullable(),
  gender: gender.optional().nullable(),
  address: optionalText(300),
});

export const bookingSchema = z.object({
  patientId: objectId,
  serviceType,
  slotDate: dateStr,
  slotStart,
  rebookFromId: objectId.optional().nullable(),
});

/** Tiếp tân tạo hồ sơ: bắt buộc họ tên, năm sinh, số điện thoại (BR.25). */
const thisYear = Number(todayVN().slice(0, 4));
export const staffPatientSchema = z.object({
  fullName,
  phone,
  birthYear: z.coerce
    .number({ invalid_type_error: 'Vui lòng nhập năm sinh.' })
    .int('Năm sinh chưa đúng.')
    .min(1900, 'Năm sinh chưa đúng.')
    .max(thisYear, 'Năm sinh chưa đúng.'),
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
