import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { futureDate, registerUser, reset, selfPatientId, setup, staffAgent, teardown } from './helpers.js';
import { Appointment, Patient, User } from '../src/models/index.js';
import { slotDateValue, slotInstant, todayVN } from '../src/utils/time.js';

before(setup);
after(teardown);
beforeEach(reset);

let seq = 0;
/** Tạo phiếu hôm nay của hồ sơ thuộc tài khoản, không phụ thuộc giờ chạy kiểm thử. */
async function todayAppointment(patientId) {
  seq += 1;
  const staff = await User.findOne({ role: 'RECEPTIONIST' });
  const patient = await Patient.findById(patientId);
  const date = todayVN();
  return Appointment.create({
    ticketCode: `CT-PK-R-${seq}`,
    userId: patient.userId,
    patientId,
    serviceType: 'CHAM_CUU',
    slotDate: slotDateValue(date),
    slotStart: '08:00',
    expectedTime: slotInstant(date, '08:00'),
    source: 'ONLINE',
    createdBy: staff._id,
  });
}

/** Đưa người bệnh vào phòng khám rồi lập phiếu kết quả. */
async function completedVisit(staff, patientId, data = { diagnosis: 'Đau lưng', doctorName: 'BS An' }) {
  const appt = await todayAppointment(patientId);
  await staff.post(`/api/staff/appointments/${appt._id}/checkin`).expect(200);
  const res = await staff.post(`/api/staff/appointments/${appt._id}/record`).send(data).expect(201);
  return { appt, record: res.body.record };
}

describe('Xem kết quả khám (BR.20, BR.21)', () => {
  it('kết quả chỉ hiện sau khi phòng khám đã ghi nhận', async () => {
    const user = await registerUser();
    const appt = await todayAppointment(await selfPatientId(user));
    await user.get(`/api/me/appointments/${appt._id}/result`).expect(404);
    assert.deepEqual((await user.get('/api/me/history').expect(200)).body.items, []);
  });

  it('chủ tài khoản xem được kết quả, không thấy người ghi và lịch sử sửa nội bộ', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const { appt } = await completedVisit(staff, await selfPatientId(user));

    const { record } = (await user.get(`/api/me/appointments/${appt._id}/result`).expect(200)).body;
    assert.equal(record.diagnosis, 'Đau lưng');
    assert.equal(record.editHistory, undefined);
    assert.equal(record.recordedByName, undefined);

    const [item] = (await user.get('/api/me/history').expect(200)).body.items;
    assert.equal(item.diagnosis, 'Đau lưng');
    assert.equal(item.editHistory, undefined);
    assert.equal(item.recordedByName, undefined);
  });

  it('tài khoản khác không xem được kết quả và lịch sử khám của người này', async () => {
    const staff = await staffAgent();
    const owner = await registerUser('0917000001', 'Chủ Hồ Sơ');
    const other = await registerUser('0917000002', 'Người Khác');
    const patientId = await selfPatientId(owner);
    const { appt } = await completedVisit(staff, patientId);

    await other.get(`/api/me/appointments/${appt._id}/result`).expect(404);
    assert.deepEqual((await other.get(`/api/me/history?patientId=${patientId}`).expect(200)).body.items, []);
    assert.deepEqual((await other.get('/api/me/history').expect(200)).body.items, []);
  });

  it('lọc lịch sử theo từng hồ sơ trong tài khoản', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const selfId = await selfPatientId(user);
    const relative = await user
      .post('/api/me/patients')
      .send({ fullName: 'Trần Thị Mẹ', birthYear: 1940, gender: 'NU', relationship: 'KHAC' })
      .expect(201);
    await completedVisit(staff, selfId, { diagnosis: 'Của mình' });
    await completedVisit(staff, relative.body.patient.id, { diagnosis: 'Của mẹ' });

    assert.equal((await user.get('/api/me/history').expect(200)).body.items.length, 2);
    const filtered = (await user.get(`/api/me/history?patientId=${relative.body.patient.id}`).expect(200)).body.items;
    assert.deepEqual(filtered.map((r) => r.diagnosis), ['Của mẹ']);
  });
});

describe('Phiếu kết quả ở khu nội bộ (UC.9.7, UC.9.9)', () => {
  it('chỉ lập phiếu cho người đang trong phòng khám', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const appt = await todayAppointment(await selfPatientId(user));
    const res = await staff.post(`/api/staff/appointments/${appt._id}/record`).send({ diagnosis: 'x' }).expect(409);
    assert.equal(res.body.error.code, 'NOT_IN_PROGRESS');
  });

  it('hồ sơ bị khoá thì không lập phiếu mới và không sửa phiếu cũ', async () => {
    const staff = await staffAgent();
    const admin = await staffAgent('admin@chuctu.vn');
    const user = await registerUser();
    const patientId = await selfPatientId(user);
    const { record } = await completedVisit(staff, patientId);
    await admin.post(`/api/admin/patients/${patientId}/lock`).expect(200);
    await staff.put(`/api/staff/records/${record.id}`).send({ diagnosis: 'Sửa' }).expect(403);

    await admin.post(`/api/admin/patients/${patientId}/unlock`).expect(200);
    const appt = await todayAppointment(patientId);
    await staff.post(`/api/staff/appointments/${appt._id}/checkin`).expect(200);
    await admin.post(`/api/admin/patients/${patientId}/lock`).expect(200);
    await staff.post(`/api/staff/appointments/${appt._id}/record`).send({ diagnosis: 'x' }).expect(403);
  });

  it('ghi lại nội dung đổi của ngày tái khám và ghi chú điều trị, sửa giống hệt thì không ghi', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const followUp = futureDate(7);
    const { record } = await completedVisit(staff, await selfPatientId(user), { diagnosis: 'Đau gối', treatmentNotes: { acupuncture: 'Huyệt A' } });

    const edited = (await staff.put(`/api/staff/records/${record.id}`).send({ followUpDate: followUp, treatmentNotes: { acupuncture: 'Huyệt B', other: 'Chườm ấm' } }).expect(200)).body.record;
    assert.equal(edited.followUpDate, followUp);
    assert.equal(edited.treatmentNotes.acupuncture, 'Huyệt B');
    assert.equal(edited.treatmentNotes.other, 'Chườm ấm');
    assert.equal(edited.editHistory.length, 1);
    const { changes } = edited.editHistory[0];
    assert.deepEqual(changes.followUpDate, { from: null, to: followUp });
    assert.deepEqual(changes['treatmentNotes.acupuncture'], { from: 'Huyệt A', to: 'Huyệt B' });
    assert.deepEqual(changes['treatmentNotes.other'], { from: null, to: 'Chườm ấm' });

    const same = (await staff.put(`/api/staff/records/${record.id}`).send({ followUpDate: followUp, treatmentNotes: { acupuncture: 'Huyệt B' } }).expect(200)).body.record;
    assert.equal(same.editHistory.length, 1);

    const cleared = (await staff.put(`/api/staff/records/${record.id}`).send({ followUpDate: null }).expect(200)).body.record;
    assert.equal(cleared.followUpDate, null);
    assert.equal(cleared.editHistory.length, 2);
  });

  it('nhân viên xem phiếu theo mã phiếu kết quả và theo phiếu khám', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const { appt, record } = await completedVisit(staff, await selfPatientId(user));

    assert.equal((await staff.get(`/api/staff/records/${record.id}`).expect(200)).body.record.diagnosis, 'Đau lưng');
    const detail = (await staff.get(`/api/staff/appointments/${appt._id}`).expect(200)).body;
    assert.equal(detail.record.id, record.id);
    await staff.get('/api/staff/records/000000000000000000000000').expect(404);
  });

  it('người dùng thường không gọi được API phiếu kết quả nội bộ', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const { record } = await completedVisit(staff, await selfPatientId(user));
    await user.get(`/api/staff/records/${record.id}`).expect(403);
    await user.put(`/api/staff/records/${record.id}`).send({ diagnosis: 'Tự sửa' }).expect(403);
  });
});
