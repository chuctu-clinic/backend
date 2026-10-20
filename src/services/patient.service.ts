import type { FilterQuery, Types } from 'mongoose';
import { rules } from '../config.ts';
import { ACTIVE_STATUSES, Appointment, MedicalRecord, Patient, nextSeq, type PatientDoc, type UserDoc } from '../models/index.ts';
import type { Gender, Relationship } from '../shared/constants.ts';
import { badRequest, conflict, forbidden, notFound } from '../utils/errors.ts';
import { escapeRegex, type Paging } from '../utils/validate.ts';
import { toDate, todayVN, yy } from '../utils/time.ts';

/** Thông tin hồ sơ khám; ngày ở dạng YYYY-MM-DD. */
export interface PatientInput {
  fullName?: string;
  dob?: string | null;
  birthYear?: number | null;
  gender?: Gender | null;
  phone?: string | null;
  address?: string | null;
  relationship?: Relationship | null;
}

interface PatientOwner {
  userId?: Types.ObjectId | null;
  relationship?: Relationship | null;
  createdBy?: Types.ObjectId | null;
}

/** Mã bệnh nhân dạng CT-BN-26-0001, đánh số lại theo từng năm. */
async function nextPatientCode() {
  const year = yy(todayVN());
  const seq = await nextSeq(`patient-${year}`);
  return `CT-BN-${year}-${String(seq).padStart(4, '0')}`;
}

export async function createPatient(
  data: PatientInput & { fullName: string },
  { userId = null, relationship = null, createdBy = null }: PatientOwner = {},
) {
  if (userId) {
    const count = await Patient.countDocuments({ userId });
    if (count >= rules.maxProfilesPerAccount) {
      throw badRequest(
        `Mỗi tài khoản lập được tối đa ${rules.maxProfilesPerAccount} hồ sơ khám. Cô chú xoá bớt hồ sơ không dùng nữa rồi thêm lại.`,
        null,
        'PROFILE_LIMIT',
      );
    }
  }
  const dob = toDate(data.dob);
  return Patient.create({
    patientCode: await nextPatientCode(),
    userId,
    relationship,
    fullName: data.fullName,
    dob,
    birthYear: data.birthYear ?? (dob ? dob.getUTCFullYear() : null),
    gender: data.gender ?? null,
    phone: data.phone ?? null,
    address: data.address ?? null,
    createdBy,
  });
}

const EDITABLE = ['fullName', 'gender', 'phone', 'address', 'relationship'] as const;

function applyUpdate(patient: PatientDoc, data: PatientInput) {
  for (const key of EDITABLE) {
    if (data[key] !== undefined) patient.set(key, data[key]);
  }
  if (data.dob !== undefined) {
    patient.dob = toDate(data.dob);
    if (patient.dob) patient.birthYear = patient.dob.getUTCFullYear();
  }
  if (data.birthYear !== undefined && !data.dob) patient.birthYear = data.birthYear;
}

// ---------- Hồ sơ khám của người dùng (bản thân và người thân) ----------

export async function listOwnProfiles(userId: Types.ObjectId) {
  return Patient.find({ userId }).sort({ relationship: 1, createdAt: 1 });
}

export async function getOwnProfile(userId: Types.ObjectId, patientId: string) {
  const patient = await Patient.findOne({ _id: patientId, userId });
  if (!patient) throw notFound('Không tìm thấy hồ sơ khám.');
  return patient;
}

export async function updateOwnProfile(userId: Types.ObjectId, patientId: string, data: PatientInput) {
  const patient = await getOwnProfile(userId, patientId);
  if (patient.isLocked) throw forbidden('Hồ sơ này đang bị phòng khám khoá nên không sửa được. Cô chú gọi phòng khám để được hỗ trợ.');
  if (patient.relationship === 'BAN_THAN' && data.relationship && data.relationship !== 'BAN_THAN') {
    throw badRequest('Không đổi quan hệ của hồ sơ chính chủ.');
  }
  applyUpdate(patient, data);
  await patient.save();
  return patient;
}

/**
 * Xoá hồ sơ khỏi danh sách của tài khoản. Hồ sơ đã có lượt khám không bị xoá cứng (BR.38):
 * hồ sơ được tách khỏi tài khoản và phòng khám vẫn giữ lịch sử.
 */
export async function deleteOwnProfile(userId: Types.ObjectId, patientId: string) {
  const patient = await getOwnProfile(userId, patientId);
  if (patient.relationship === 'BAN_THAN') throw badRequest('Không xoá được hồ sơ của chính chủ tài khoản.');
  const active = await Appointment.exists({ patientId: patient._id, status: { $in: ACTIVE_STATUSES } });
  if (active) {
    throw conflict('Hồ sơ này còn phiếu khám chưa hoàn thành. Cô chú huỷ phiếu khám trước rồi xoá hồ sơ.', 'PROFILE_IN_USE');
  }
  const hasHistory =
    (await Appointment.exists({ patientId: patient._id })) || (await MedicalRecord.exists({ patientId: patient._id }));
  if (hasHistory) {
    patient.userId = null;
    await patient.save();
    return { detached: true };
  }
  await patient.deleteOne();
  return { deleted: true };
}

// ---------- Quản lý bệnh nhân (Tiếp tân, Admin) ----------

export async function searchPatients({ q, locked, skip, limit }: { q?: string; locked?: string } & Paging) {
  const filter: FilterQuery<PatientDoc> = {};
  if (q) {
    const rx = new RegExp(escapeRegex(q.trim()), 'i');
    filter.$or = [{ fullName: rx }, { patientCode: rx }, { phone: rx }];
  }
  if (locked === 'true') filter.isLocked = true;
  if (locked === 'false') filter.isLocked = false;
  const [items, total] = await Promise.all([
    Patient.find(filter).sort({ createdAt: -1 }).skip(skip).limit(limit),
    Patient.countDocuments(filter),
  ]);
  return { items, total };
}

export async function findDuplicatesByPhone(phone: string | null | undefined, excludeId: string | null = null) {
  if (!phone) return [];
  const filter: FilterQuery<PatientDoc> = { phone };
  if (excludeId) filter._id = { $ne: excludeId };
  return Patient.find(filter).limit(10);
}

/** Tiếp tân tạo hồ sơ: cảnh báo trùng số điện thoại trước khi lưu (BR.25, BR.35). */
export async function staffCreatePatient(data: PatientInput & { fullName: string }, staff: UserDoc, { confirmDuplicate = false } = {}) {
  if (!confirmDuplicate) {
    const dups = await findDuplicatesByPhone(data.phone);
    if (dups.length) {
      throw conflict('Đã có hồ sơ dùng số điện thoại này. Kiểm tra lại trước khi tạo hồ sơ mới.', 'DUPLICATE_PHONE', {
        duplicates: dups.map((p) => p.toPublic()),
      });
    }
  }
  return createPatient(data, { createdBy: staff._id });
}

export async function getPatient(patientId: string) {
  const patient = await Patient.findById(patientId);
  if (!patient) throw notFound('Không tìm thấy hồ sơ bệnh nhân.');
  return patient;
}

export async function staffUpdatePatient(patientId: string, data: PatientInput) {
  const patient = await getPatient(patientId);
  if (patient.isLocked) throw forbidden('Hồ sơ đang bị khoá nên không sửa được thông tin.');
  applyUpdate(patient, data);
  await patient.save();
  return patient;
}

export async function setPatientLock(patientId: string, locked: boolean) {
  const patient = await getPatient(patientId);
  patient.isLocked = locked;
  patient.lockedAt = locked ? new Date() : null;
  await patient.save();
  return patient;
}
