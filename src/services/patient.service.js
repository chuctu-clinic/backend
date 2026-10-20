import { rules } from '../config.js';
import { ACTIVE_STATUSES, Appointment, MedicalRecord, Patient, nextSeq } from '../models/index.js';
import { badRequest, conflict, forbidden, notFound } from '../utils/errors.js';
import { escapeRegex } from '../utils/validate.js';
import { todayVN, yy } from '../utils/time.js';

/** Mã bệnh nhân dạng CT-BN-26-0001, đánh số lại theo từng năm. */
async function nextPatientCode() {
  const year = yy(todayVN());
  const seq = await nextSeq(`patient-${year}`);
  return `CT-BN-${year}-${String(seq).padStart(4, '0')}`;
}

function toDate(dateStr) {
  return dateStr ? new Date(`${dateStr}T00:00:00Z`) : null;
}

export async function createPatient(data, { userId = null, relationship = null, createdBy = null } = {}) {
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

function applyUpdate(patient, data) {
  for (const key of ['fullName', 'gender', 'phone', 'address', 'relationship']) {
    if (data[key] !== undefined) patient[key] = data[key];
  }
  if (data.dob !== undefined) {
    patient.dob = toDate(data.dob);
    if (patient.dob) patient.birthYear = patient.dob.getUTCFullYear();
  }
  if (data.birthYear !== undefined && !data.dob) patient.birthYear = data.birthYear;
}

// ---------- Hồ sơ khám của người dùng (bản thân và người thân) ----------

export async function listOwnProfiles(userId) {
  return Patient.find({ userId }).sort({ relationship: 1, createdAt: 1 });
}

export async function getOwnProfile(userId, patientId) {
  const patient = await Patient.findOne({ _id: patientId, userId });
  if (!patient) throw notFound('Không tìm thấy hồ sơ khám.');
  return patient;
}

export async function updateOwnProfile(userId, patientId, data) {
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
export async function deleteOwnProfile(userId, patientId) {
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

export async function searchPatients({ q, locked, skip, limit }) {
  const filter = {};
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

export async function findDuplicatesByPhone(phone, excludeId = null) {
  if (!phone) return [];
  const filter = { phone };
  if (excludeId) filter._id = { $ne: excludeId };
  return Patient.find(filter).limit(10);
}

/** Tiếp tân tạo hồ sơ: cảnh báo trùng số điện thoại trước khi lưu (BR.25, BR.35). */
export async function staffCreatePatient(data, staff, { confirmDuplicate = false } = {}) {
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

export async function getPatient(patientId) {
  const patient = await Patient.findById(patientId);
  if (!patient) throw notFound('Không tìm thấy hồ sơ bệnh nhân.');
  return patient;
}

export async function staffUpdatePatient(patientId, data) {
  const patient = await getPatient(patientId);
  if (patient.isLocked) throw forbidden('Hồ sơ đang bị khoá nên không sửa được thông tin.');
  applyUpdate(patient, data);
  await patient.save();
  return patient;
}

export async function setPatientLock(patientId, locked) {
  const patient = await getPatient(patientId);
  patient.isLocked = locked;
  patient.lockedAt = locked ? new Date() : null;
  await patient.save();
  return patient;
}
