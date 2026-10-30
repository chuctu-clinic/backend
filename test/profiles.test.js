import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { app, futureDate, registerUser, request, reset, selfPatientId, setup, staffAgent, teardown } from './helpers.js';
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

describe('Hồ sơ chính chủ luôn khớp với tài khoản', () => {
  const profileEdit = { fullName: 'Tên Hồ Sơ', dob: '1960-02-03', gender: 'NU', address: 'Quận 3' };
  const account = async (agent) => (await agent.get('/api/me').expect(200)).body.user;

  it('sửa hồ sơ chính chủ thì tài khoản cập nhật theo và trả về tài khoản mới', async () => {
    const user = await registerUser();
    const res = await user.patch(`/api/me/patients/${await selfPatientId(user)}`).send(profileEdit).expect(200);
    assert.equal(res.body.user.fullName, 'Tên Hồ Sơ');
    const me = await account(user);
    assert.equal(me.fullName, 'Tên Hồ Sơ');
    assert.equal(me.gender, 'NU');
    assert.equal(me.address, 'Quận 3');
    assert.equal(me.dob.slice(0, 10), '1960-02-03');
  });

  it('sửa hồ sơ người thân không đụng tới tài khoản', async () => {
    const user = await registerUser('0918000021', 'Chủ Tài Khoản');
    const relative = await addRelative(user);
    const res = await user.patch(`/api/me/patients/${relative.id}`).send(profileEdit).expect(200);
    assert.equal(res.body.user, undefined);
    assert.equal((await account(user)).fullName, 'Chủ Tài Khoản');
  });

  it('người dùng không đổi được số điện thoại của hồ sơ chính chủ, vẫn đổi được số của người thân', async () => {
    const user = await registerUser('0918000031');
    const selfId = await selfPatientId(user);
    const res = await user.patch(`/api/me/patients/${selfId}`).send({ phone: '0918000032' }).expect(400);
    assert.ok(res.body.error.fields.phone);
    await user.patch(`/api/me/patients/${selfId}`).send({ phone: '0918000031', fullName: 'Tên Mới' }).expect(200);
    const relative = await addRelative(user);
    await user.patch(`/api/me/patients/${relative.id}`).send({ phone: '0918000032' }).expect(200);
  });

  it('tiếp tân sửa hồ sơ chính chủ thì tài khoản đổi theo, nhưng không đổi được số đăng nhập', async () => {
    const staff = await staffAgent();
    const user = await registerUser('0918000041');
    const selfId = await selfPatientId(user);
    await staff.patch(`/api/staff/patients/${selfId}`).send({ fullName: 'Tên Theo Giấy Tờ' }).expect(200);
    assert.equal((await account(user)).fullName, 'Tên Theo Giấy Tờ');
    const res = await staff.patch(`/api/staff/patients/${selfId}`).send({ phone: '0918000042' }).expect(400);
    assert.ok(res.body.error.fields.phone);
    assert.equal((await account(user)).phone, '0918000041');
  });

  it('hồ sơ chưa có ngày sinh thì tài khoản giữ nguyên ngày sinh', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const selfId = await selfPatientId(user);
    await user.patch('/api/me').send({ dob: '1970-05-06' }).expect(200);
    await Patient.updateOne({ _id: selfId }, { $set: { dob: null } });
    await staff.patch(`/api/staff/patients/${selfId}`).send({ fullName: 'Tên Khác' }).expect(200);
    assert.equal((await account(user)).dob.slice(0, 10), '1970-05-06');
  });

  it('Admin sửa tài khoản thì hồ sơ chính chủ đổi theo, cả số điện thoại', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const user = await registerUser('0918000051');
    const selfId = await selfPatientId(user);
    const { id } = await account(user);
    await admin.patch(`/api/admin/accounts/${id}`).send({ fullName: 'Tên Do Admin', phone: '0918000052' }).expect(200);
    const self = await Patient.findById(selfId);
    assert.equal(self.fullName, 'Tên Do Admin');
    assert.equal(self.phone, '0918000052');
  });

  it('hồ sơ chính chủ đang khoá chỉ nhận số đăng nhập, mở khoá thì nhận nốt các ô còn lại', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const user = await registerUser('0918000061', 'Tên Cũ');
    const selfId = await selfPatientId(user);
    const { id } = await account(user);
    await admin.post(`/api/admin/patients/${selfId}/lock`).expect(200);
    await admin.patch(`/api/admin/accounts/${id}`).send({ fullName: 'Tên Mới', phone: '0918000062' }).expect(200);

    const locked = await Patient.findById(selfId);
    assert.equal(locked.fullName, 'Tên Cũ');
    assert.equal(locked.phone, '0918000062');

    const { patient } = (await admin.post(`/api/admin/patients/${selfId}/unlock`).expect(200)).body;
    assert.equal(patient.fullName, 'Tên Mới');
    assert.equal(patient.phone, '0918000062');
  });

  it('không xoá trắng được ngày sinh, địa chỉ, giới tính của hồ sơ chính chủ', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const selfId = await selfPatientId(user);
    assert.ok((await user.patch(`/api/me/patients/${selfId}`).send({ dob: null }).expect(400)).body.error.fields.dob);
    assert.ok((await user.patch(`/api/me/patients/${selfId}`).send({ address: '' }).expect(400)).body.error.fields.address);
    assert.ok((await staff.patch(`/api/staff/patients/${selfId}`).send({ gender: null, dob: null }).expect(400)).body.error.fields.gender);
    const self = await Patient.findById(selfId);
    assert.ok(self.dob && self.address && self.gender);
  });

  it('hồ sơ người thân vẫn xoá được địa chỉ', async () => {
    const user = await registerUser();
    const relative = await addRelative(user, { ...RELATIVE, address: 'Bình Thạnh' });
    const { patient } = (await user.patch(`/api/me/patients/${relative.id}`).send({ address: '' }).expect(200)).body;
    assert.equal(patient.address, null);
  });

  it('Admin không xoá trắng được địa chỉ tài khoản', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const user = await registerUser();
    const { id } = await account(user);
    await admin.patch(`/api/admin/accounts/${id}`).send({ address: '' }).expect(400);
    await admin.patch(`/api/admin/accounts/${id}`).send({ address: null }).expect(400);
    assert.equal((await account(user)).address, 'Bình Thạnh');
  });
});

describe('Hai nơi cùng sửa một người (không đè mất thay đổi)', () => {
  it('Admin vừa đổi tên thì người dùng lưu hồ sơ bản cũ bị từ chối, tải lại rồi lưu được', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const user = await registerUser();
    const selfId = await selfPatientId(user);
    const stale = (await user.get(`/api/me/patients/${selfId}`).expect(200)).body.patient;
    const { id } = (await user.get('/api/me').expect(200)).body.user;
    await admin.patch(`/api/admin/accounts/${id}`).send({ fullName: 'Tên Do Admin' }).expect(200);

    const res = await user.patch(`/api/me/patients/${selfId}`).send({ fullName: 'Tên Cũ Sửa', expectedUpdatedAt: stale.updatedAt }).expect(409);
    assert.equal(res.body.error.code, 'STALE');
    assert.equal((await Patient.findById(selfId)).fullName, 'Tên Do Admin');

    const fresh = (await user.get(`/api/me/patients/${selfId}`).expect(200)).body.patient;
    await user.patch(`/api/me/patients/${selfId}`).send({ fullName: 'Tên Mới', expectedUpdatedAt: fresh.updatedAt }).expect(200);
  });

  it('người dùng vừa đổi hồ sơ thì Admin lưu tài khoản bản cũ bị từ chối', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const user = await registerUser();
    const { id } = (await user.get('/api/me').expect(200)).body.user;
    const stale = (await admin.get(`/api/admin/accounts/${id}`).expect(200)).body.account;
    await user.patch(`/api/me/patients/${await selfPatientId(user)}`).send({ fullName: 'Tên Do Người Dùng' }).expect(200);
    const res = await admin.patch(`/api/admin/accounts/${id}`).send({ fullName: 'Tên Admin', expectedUpdatedAt: stale.infoUpdatedAt }).expect(409);
    assert.equal(res.body.error.code, 'STALE');
  });

  it('tiếp tân và trang Thông tin tài khoản cũng phát hiện bản cũ', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const selfId = await selfPatientId(user);
    const staleMe = (await user.get('/api/me').expect(200)).body.user;
    const stalePatient = (await staff.get(`/api/staff/patients/${selfId}`).expect(200)).body.patient;

    await staff.patch(`/api/staff/patients/${selfId}`).send({ fullName: 'Tên Quầy', expectedUpdatedAt: stalePatient.updatedAt }).expect(200);
    await staff.patch(`/api/staff/patients/${selfId}`).send({ fullName: 'Tên Quầy 2', expectedUpdatedAt: stalePatient.updatedAt }).expect(409);
    await user.patch('/api/me').send({ fullName: 'Tên Cũ', expectedUpdatedAt: staleMe.infoUpdatedAt }).expect(409);
  });

  it('đăng nhập lại không làm bản đang sửa thành bản cũ', async () => {
    const user = await registerUser('0918000071');
    const { infoUpdatedAt } = (await user.get('/api/me').expect(200)).body.user;
    await user.post('/api/auth/login').send({ phone: '0918000071' }).expect(200);
    await user.patch('/api/me').send({ fullName: 'Tên Mới', expectedUpdatedAt: infoUpdatedAt }).expect(200);
  });

  it('không gửi mốc thời gian thì vẫn lưu như thường', async () => {
    const user = await registerUser();
    await user.patch(`/api/me/patients/${await selfPatientId(user)}`).send({ fullName: 'Tên Mới' }).expect(200);
    await user.patch('/api/me').send({ fullName: 'Tên Khác', expectedUpdatedAt: 'khong-phai-ngay' }).expect(400);
  });
});

describe('Ngày tháng và năm sinh', () => {
  it('từ chối ngày không có thật, không tự đẩy sang tháng sau', async () => {
    const user = await registerUser();
    for (const dob of ['1950-02-30', '1950-04-31', '1951-02-29']) {
      const res = await user.post('/api/me/patients').send({ ...RELATIVE, birthYear: undefined, dob }).expect(400);
      assert.ok(res.body.error.fields.dob, dob);
    }
    await user.post('/api/me/patients').send({ ...RELATIVE, birthYear: undefined, dob: '1952-02-29' }).expect(201);
    const booked = await book(user, await selfPatientId(user), '2026-02-30').expect(400);
    assert.ok(booked.body.error.fields.slotDate);
  });

  it('có ngày sinh thì năm sinh đi theo ngày sinh', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const created = await addRelative(user, { ...RELATIVE, dob: '1950-01-02', birthYear: 1999 });
    assert.equal(created.birthYear, 1950);
    const res = await staff.patch(`/api/staff/patients/${created.id}`).send({ birthYear: 1970 }).expect(400);
    assert.ok(res.body.error.fields.birthYear);
    await staff.patch(`/api/staff/patients/${created.id}`).send({ birthYear: 1950 }).expect(200);
    assert.equal((await Patient.findById(created.id)).birthYear, 1950);
  });
});

describe('Hồ sơ chính chủ thiếu thông tin (tài khoản do Admin tạo)', () => {
  it('phải bổ sung ngày sinh, giới tính, địa chỉ rồi mới đặt được lịch', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    await admin.post('/api/admin/accounts').send({ fullName: 'Khách Admin Tạo', phone: '0918000081', role: 'USER' }).expect(201);
    const user = request.agent(app);
    const login = await user.post('/api/auth/login').send({ phone: '0918000081' }).expect(200);
    await user.post('/api/auth/login/verify').send({ phone: '0918000081', code: login.body.devCode }).expect(200);
    const selfId = await selfPatientId(user);

    const res = await book(user, selfId).expect(400);
    assert.equal(res.body.error.code, 'PROFILE_INCOMPLETE');
    await user.patch(`/api/me/patients/${selfId}`).send({ dob: '1970-01-01', gender: 'NAM' }).expect(200);
    await book(user, selfId).expect(400);
    await user.patch(`/api/me/patients/${selfId}`).send({ address: 'Bình Thạnh' }).expect(200);
    await book(user, selfId).expect(201);
  });

  it('tiếp tân vẫn đặt hộ được khi hồ sơ thiếu thông tin', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const { account } = (await admin.post('/api/admin/accounts').send({ fullName: 'Khách Tại Quầy', phone: '0918000091', role: 'USER' }).expect(201)).body;
    const self = await Patient.findOne({ userId: account.id });
    assert.equal(self.address, null);
    const staff = await staffAgent();
    await staff.post('/api/staff/appointments').send({ patientId: self.id, serviceType: 'CHAM_CUU', slotDate: futureDate(1), slotStart: '09:00' }).expect(201);
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
