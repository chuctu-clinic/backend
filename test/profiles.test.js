import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { futureDate, registerUser, reset, selfPatientId, setup, staffAgent, teardown } from './helpers.js';
import { Patient } from '../src/models/index.js';

before(setup);
after(teardown);
beforeEach(reset);

const RELATIVE = { fullName: 'Lê Thị Mẹ', birthYear: 1945, gender: 'NU', relationship: 'KHAC' };
const addRelative = async (agent, data = RELATIVE) => (await agent.post('/api/me/patients').send(data).expect(201)).body.patient;
const book = (agent, patientId, slotDate = futureDate(2)) =>
  agent.post('/api/me/appointments').send({ patientId, serviceType: 'CHAM_CUU', slotDate, slotStart: '09:00' });

describe('Thông tin tài khoản (UC.1.4, UC.1.5)', () => {
  it('không tự đổi được số điện thoại đăng nhập (BR.01)', async () => {
    const user = await registerUser('0918000001');
    const res = await user.patch('/api/me').send({ phone: '0918000002' }).expect(400);
    assert.match(res.body.error.message, /không tự sửa được/);
    assert.equal((await user.get('/api/me').expect(200)).body.user.phone, '0918000001');
    await user.patch('/api/me').send({ phone: '0918000001', fullName: 'Tên Mới' }).expect(200);
  });

  it('sửa tài khoản thì hồ sơ chính chủ cập nhật theo', async () => {
    const user = await registerUser();
    await user.patch('/api/me').send({ fullName: 'Tên Mới', dob: '1970-05-06', gender: 'NU', address: 'Quận 1' }).expect(200);
    const self = await Patient.findById(await selfPatientId(user));
    assert.equal(self.fullName, 'Tên Mới');
    assert.equal(self.gender, 'NU');
    assert.equal(self.address, 'Quận 1');
    assert.equal(self.birthYear, 1970);
  });

  it('không xoá trắng được ngày sinh, địa chỉ', async () => {
    const user = await registerUser();
    const res = await user.patch('/api/me').send({ dob: '', address: '  ' }).expect(400);
    assert.ok(res.body.error.fields.dob);
    assert.ok(res.body.error.fields.address);
  });
});

describe('Hồ sơ khám của tài khoản (UC.1.6 – UC.1.8)', () => {
  it('tài khoản chỉ có một hồ sơ chính chủ', async () => {
    const user = await registerUser();
    await user.post('/api/me/patients').send({ ...RELATIVE, relationship: 'BAN_THAN' }).expect(400);
    const relative = await addRelative(user);
    await user.patch(`/api/me/patients/${relative.id}`).send({ relationship: 'BAN_THAN' }).expect(400);
  });

  it('không đổi quan hệ của hồ sơ chính chủ', async () => {
    const user = await registerUser();
    await user.patch(`/api/me/patients/${await selfPatientId(user)}`).send({ relationship: 'CON' }).expect(400);
  });

  it('hồ sơ cần ngày sinh hoặc năm sinh', async () => {
    const user = await registerUser();
    const res = await user.post('/api/me/patients').send({ fullName: 'Lê Văn Con', gender: 'NAM', relationship: 'CON' }).expect(400);
    assert.ok(res.body.error.fields.dob);
  });

  it('sửa hồ sơ người thân của mình, ô không gửi thì giữ nguyên', async () => {
    const user = await registerUser();
    const relative = await addRelative(user, { ...RELATIVE, address: 'Bình Thạnh' });
    const { patient } = (await user.patch(`/api/me/patients/${relative.id}`).send({ fullName: 'Lê Thị Bà' }).expect(200)).body;
    assert.equal(patient.fullName, 'Lê Thị Bà');
    assert.equal(patient.address, 'Bình Thạnh');
    assert.equal((await user.get(`/api/me/patients/${relative.id}`).expect(200)).body.patient.fullName, 'Lê Thị Bà');
  });

  it('không xem, sửa, xoá hay đặt lịch được cho hồ sơ của tài khoản khác', async () => {
    const owner = await registerUser('0918000011', 'Chủ Hồ Sơ');
    const other = await registerUser('0918000012', 'Người Khác');
    const relative = await addRelative(owner);

    await other.get(`/api/me/patients/${relative.id}`).expect(404);
    await other.patch(`/api/me/patients/${relative.id}`).send({ fullName: 'Tên Lạ' }).expect(404);
    await other.delete(`/api/me/patients/${relative.id}`).expect(404);
    await book(other, relative.id).expect(404);
    assert.equal((await owner.get(`/api/me/patients/${relative.id}`).expect(200)).body.patient.fullName, RELATIVE.fullName);
  });

  it('hồ sơ bị khoá thì không sửa được và không đặt lịch được', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const user = await registerUser();
    const relative = await addRelative(user);
    await admin.post(`/api/admin/patients/${relative.id}/lock`).expect(200);
    await user.patch(`/api/me/patients/${relative.id}`).send({ fullName: 'Tên Khác' }).expect(403);
    await book(user, relative.id).expect(403);
  });

  it('không xoá được hồ sơ chính chủ', async () => {
    const user = await registerUser();
    await user.delete(`/api/me/patients/${await selfPatientId(user)}`).expect(400);
  });

  it('hồ sơ chưa từng khám thì xoá hẳn', async () => {
    const user = await registerUser();
    const relative = await addRelative(user);
    assert.deepEqual((await user.delete(`/api/me/patients/${relative.id}`).expect(200)).body, { deleted: true });
    assert.equal(await Patient.exists({ _id: relative.id }), null);
  });

  it('hồ sơ còn phiếu chưa hoàn thành thì chưa xoá được', async () => {
    const user = await registerUser();
    const relative = await addRelative(user);
    await book(user, relative.id).expect(201);
    const res = await user.delete(`/api/me/patients/${relative.id}`).expect(409);
    assert.equal(res.body.error.code, 'PROFILE_IN_USE');
  });

  it('hồ sơ đã có phiếu khám thì tách khỏi tài khoản, phòng khám vẫn giữ (BR.38)', async () => {
    const user = await registerUser();
    const relative = await addRelative(user);
    const appt = (await book(user, relative.id).expect(201)).body.appointment;
    await user.post(`/api/me/appointments/${appt.id}/cancel`).send({}).expect(200);

    assert.deepEqual((await user.delete(`/api/me/patients/${relative.id}`).expect(200)).body, { detached: true });
    const kept = await Patient.findById(relative.id);
    assert.equal(kept.userId, null);
    const list = (await user.get('/api/me/patients').expect(200)).body.items;
    assert.ok(!list.some((p) => p.id === relative.id));
  });
});
