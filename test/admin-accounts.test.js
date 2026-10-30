import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { app, registerUser, request, reset, setup, staffAgent, teardown } from './helpers.js';
import { Patient, User } from '../src/models/index.js';

before(setup);
after(teardown);
beforeEach(reset);

const TEMP = 'MatKhauTam@1';
const adminAgent = () => staffAgent('admin@chuctu.vn');
const staffLogin = (identifier, password) => request(app).post('/api/auth/staff/login').send({ identifier, password });
const newAccount = (over = {}) => ({ fullName: 'Tài Khoản Mới', phone: '0920000001', role: 'USER', ...over });
const idOf = async (phone) => (await User.findOne({ phone }))._id.toString();

describe('Phân quyền Quản lý tài khoản (BR.05, BR.10, BR.31)', () => {
  it('chỉ Admin dùng được', async () => {
    const receptionist = await staffAgent();
    const user = await registerUser();
    await request(app).get('/api/admin/accounts').expect(401);
    await receptionist.get('/api/admin/accounts').expect(403);
    await user.get('/api/admin/accounts').expect(403);
    await receptionist.post('/api/admin/accounts').send(newAccount()).expect(403);
    await user.put(`/api/admin/accounts/${await idOf('0911000001')}/role`).send({ role: 'ADMIN', tempPassword: TEMP }).expect(403);
    assert.equal((await User.findOne({ phone: '0911000001' })).role, 'USER');
  });
});

describe('Tạo và sửa tài khoản (UC.8.1 – UC.8.3)', () => {
  it('tài khoản người dùng có sẵn hồ sơ chính chủ, không có mật khẩu', async () => {
    const admin = await adminAgent();
    const { account } = (await admin.post('/api/admin/accounts').send(newAccount()).expect(201)).body;
    assert.equal(account.role, 'USER');
    assert.ok(await Patient.exists({ userId: account.id, relationship: 'BAN_THAN' }));
    assert.equal((await User.findById(account.id).select('+passwordHash')).passwordHash, null);
  });

  it('tài khoản nội bộ cần mật khẩu tạm và phải đổi khi đăng nhập lần đầu (BR.33)', async () => {
    const admin = await adminAgent();
    const body = newAccount({ role: 'RECEPTIONIST', email: 'moi@chuctu.vn' });
    const res = await admin.post('/api/admin/accounts').send(body).expect(400);
    assert.ok(res.body.error.fields.tempPassword);
    await admin.post('/api/admin/accounts').send({ ...body, tempPassword: 'ngan' }).expect(400);

    await admin.post('/api/admin/accounts').send({ ...body, tempPassword: TEMP }).expect(201);
    assert.equal((await User.findOne({ phone: body.phone })).mustChangePassword, true);
    await staffLogin('moi@chuctu.vn', TEMP).expect(200);
    await staffLogin('moi@chuctu.vn', 'SaiMatKhau@1').expect(401);
  });

  it('tài khoản nội bộ bắt buộc có email để dùng Quên mật khẩu', async () => {
    const admin = await adminAgent();
    for (const role of ['RECEPTIONIST', 'ADMIN']) {
      const res = await admin.post('/api/admin/accounts').send(newAccount({ role, tempPassword: TEMP })).expect(400);
      assert.equal(res.body.error.code, 'EMAIL_REQUIRED');
      assert.ok(res.body.error.fields.email);
      await admin.post('/api/admin/accounts').send(newAccount({ role, tempPassword: TEMP, email: '' })).expect(400);
    }
    assert.equal(await User.exists({ phone: newAccount().phone }), null);
    // Tài khoản người dùng vẫn không cần email
    await admin.post('/api/admin/accounts').send(newAccount()).expect(201);
  });

  it('không xoá được email của tài khoản nội bộ, tài khoản người dùng thì được', async () => {
    const admin = await adminAgent();
    const receptionist = await User.findOne({ role: 'RECEPTIONIST' });
    await admin.patch(`/api/admin/accounts/${receptionist._id}`).send({ email: '' }).expect(400);
    await admin.patch(`/api/admin/accounts/${receptionist._id}`).send({ email: null }).expect(400);
    assert.equal((await User.findById(receptionist._id)).email, 'letan@chuctu.vn');

    const { account } = (await admin.post('/api/admin/accounts').send(newAccount({ email: 'nguoidung@chuctu.vn' })).expect(201)).body;
    await admin.patch(`/api/admin/accounts/${account.id}`).send({ email: '' }).expect(200);
  });

  it('số điện thoại và email không được trùng tài khoản khác', async () => {
    const admin = await adminAgent();
    const first = (await admin.post('/api/admin/accounts').send(newAccount({ role: 'RECEPTIONIST', email: 'a@chuctu.vn', tempPassword: TEMP })).expect(201)).body.account;
    const second = (await admin.post('/api/admin/accounts').send(newAccount({ phone: '0920000002' })).expect(201)).body.account;

    const dup = await admin.post('/api/admin/accounts').send(newAccount()).expect(409);
    assert.equal(dup.body.error.code, 'PHONE_TAKEN');
    const phone = await admin.patch(`/api/admin/accounts/${second.id}`).send({ phone: first.phone }).expect(409);
    assert.equal(phone.body.error.code, 'PHONE_TAKEN');
    const email = await admin.patch(`/api/admin/accounts/${second.id}`).send({ email: 'a@chuctu.vn' }).expect(409);
    assert.equal(email.body.error.code, 'EMAIL_TAKEN');
    // Giữ nguyên số của chính mình thì không tính là trùng
    await admin.patch(`/api/admin/accounts/${first.id}`).send({ phone: first.phone, fullName: 'Tên Đã Sửa' }).expect(200);
  });

  it('lọc danh sách theo vai trò, trạng thái, từ khoá', async () => {
    const admin = await adminAgent();
    const { account } = (await admin.post('/api/admin/accounts').send(newAccount({ fullName: 'Người Cần Tìm' })).expect(201)).body;
    await admin.post(`/api/admin/accounts/${account.id}/lock`).expect(200);

    const list = async (qs) => (await admin.get(`/api/admin/accounts?${qs}`).expect(200)).body;
    assert.deepEqual((await list('q=Cần Tìm')).items.map((a) => a.id), [account.id]);
    assert.deepEqual((await list('status=locked')).items.map((a) => a.id), [account.id]);
    assert.ok((await list('role=ADMIN')).items.every((a) => a.role === 'ADMIN'));
    assert.ok(!(await list('status=active')).items.some((a) => a.id === account.id));
    assert.equal((await list('')).roleCounts.USER, 1);
  });

  it('không tìm thấy tài khoản thì báo 404', async () => {
    const admin = await adminAgent();
    await admin.get('/api/admin/accounts/000000000000000000000000').expect(404);
  });
});

describe('Khoá tài khoản (BR.33)', () => {
  it('khoá xong thì phiên đang mở bị huỷ, không đăng nhập được; mở khoá thì đăng nhập lại được', async () => {
    const admin = await adminAgent();
    const user = await registerUser();
    await user.get('/api/me').expect(200);
    const id = await idOf('0911000001');

    await admin.post(`/api/admin/accounts/${id}/lock`).expect(200);
    await user.get('/api/me').expect(401);
    await user.post('/api/auth/login').send({ phone: '0911000001' }).expect(403);

    await admin.post(`/api/admin/accounts/${id}/unlock`).expect(200);
    // Phiên cũ đã bị huỷ lúc khoá, mở khoá không tự khôi phục
    await user.get('/api/me').expect(401);
    await user.post('/api/auth/login').send({ phone: '0911000001' }).expect(200);
    await user.get('/api/me').expect(200);
  });

  it('không tự khoá tài khoản đang đăng nhập', async () => {
    const admin = await adminAgent();
    const me = await User.findOne({ role: 'ADMIN' });
    await admin.post(`/api/admin/accounts/${me._id}/lock`).expect(400);
    assert.equal((await User.findById(me._id)).isActive, true);
  });
});

describe('Đổi vai trò và đặt lại mật khẩu (BR.32, UC.8.5)', () => {
  it('lên vai trò nội bộ mà chưa có mật khẩu thì phải đặt mật khẩu tạm', async () => {
    const admin = await adminAgent();
    await registerUser();
    const id = await idOf('0911000001');

    const res = await admin.put(`/api/admin/accounts/${id}/role`).send({ role: 'RECEPTIONIST' }).expect(400);
    assert.equal(res.body.error.code, 'PASSWORD_REQUIRED');
    assert.equal((await User.findById(id)).role, 'USER');

    const noEmail = await admin.put(`/api/admin/accounts/${id}/role`).send({ role: 'RECEPTIONIST', tempPassword: TEMP }).expect(400);
    assert.equal(noEmail.body.error.code, 'EMAIL_REQUIRED');
    const taken = await admin.put(`/api/admin/accounts/${id}/role`).send({ role: 'RECEPTIONIST', tempPassword: TEMP, email: 'admin@chuctu.vn' }).expect(409);
    assert.equal(taken.body.error.code, 'EMAIL_TAKEN');
    assert.equal((await User.findById(id)).role, 'USER');

    const { account } = (await admin.put(`/api/admin/accounts/${id}/role`).send({ role: 'RECEPTIONIST', tempPassword: TEMP, email: 'LenVaiTro@chuctu.vn' }).expect(200)).body;
    assert.equal(account.role, 'RECEPTIONIST');
    assert.equal(account.email, 'lenvaitro@chuctu.vn');
    assert.equal((await User.findById(id)).mustChangePassword, true);
    await staffLogin('0911000001', TEMP).expect(200);
  });

  it('đã có mật khẩu nhưng chưa có email thì đổi vai trò chỉ cần nhập email', async () => {
    const admin = await adminAgent();
    const receptionist = await User.findOne({ role: 'RECEPTIONIST' });
    await User.updateOne({ _id: receptionist._id }, { $unset: { email: 1 } });
    const res = await admin.put(`/api/admin/accounts/${receptionist._id}/role`).send({ role: 'ADMIN' }).expect(400);
    assert.equal(res.body.error.code, 'EMAIL_REQUIRED');
    await admin.put(`/api/admin/accounts/${receptionist._id}/role`).send({ role: 'ADMIN', email: 'moi@chuctu.vn' }).expect(200);
  });

  it('đã có mật khẩu thì đổi vai trò không cần mật khẩu tạm', async () => {
    const admin = await adminAgent();
    const receptionist = await User.findOne({ role: 'RECEPTIONIST' });
    await admin.put(`/api/admin/accounts/${receptionist._id}/role`).send({ role: 'ADMIN' }).expect(200);
    assert.equal((await User.findById(receptionist._id)).role, 'ADMIN');
  });

  it('Admin không tự hạ quyền chính mình, vai trò lạ bị từ chối', async () => {
    const admin = await adminAgent();
    const me = await User.findOne({ role: 'ADMIN' });
    await admin.put(`/api/admin/accounts/${me._id}/role`).send({ role: 'RECEPTIONIST' }).expect(400);
    await admin.put(`/api/admin/accounts/${me._id}/role`).send({ role: 'THANH_VIEN' }).expect(400);
    assert.equal((await User.findById(me._id)).role, 'ADMIN');
  });

  it('đặt lại mật khẩu: mật khẩu cũ hết tác dụng, phải đổi khi đăng nhập', async () => {
    const admin = await adminAgent();
    const receptionist = await User.findOne({ role: 'RECEPTIONIST' });
    await admin.post(`/api/admin/accounts/${receptionist._id}/reset-password`).send({ tempPassword: TEMP }).expect(200);

    await staffLogin('letan@chuctu.vn', 'ChucTu@2026').expect(401);
    await staffLogin('letan@chuctu.vn', TEMP).expect(200);
    assert.equal((await User.findById(receptionist._id)).mustChangePassword, true);
  });

  it('đặt lại mật khẩu thì phiên cũ của người đó không làm việc được nữa', async () => {
    const admin = await adminAgent();
    const staff = await staffAgent();
    const receptionist = await User.findOne({ role: 'RECEPTIONIST' });
    await staff.get('/api/staff/queue').expect(200);
    await admin.post(`/api/admin/accounts/${receptionist._id}/reset-password`).send({ tempPassword: TEMP }).expect(200);
    await staff.get('/api/staff/queue').expect(403);
  });

  it('không tự đặt lại mật khẩu, tiếp tân không gọi được, mật khẩu tạm phải đủ dài', async () => {
    const admin = await adminAgent();
    const staff = await staffAgent();
    const me = await User.findOne({ role: 'ADMIN' });
    const receptionist = await User.findOne({ role: 'RECEPTIONIST' });
    await admin.post(`/api/admin/accounts/${me._id}/reset-password`).send({ tempPassword: TEMP }).expect(400);
    await admin.post(`/api/admin/accounts/${receptionist._id}/reset-password`).send({ tempPassword: 'ngan' }).expect(400);
    await staff.post(`/api/admin/accounts/${me._id}/reset-password`).send({ tempPassword: TEMP }).expect(403);
  });

  it('tài khoản người dùng không có mật khẩu để đặt lại', async () => {
    const admin = await adminAgent();
    await registerUser();
    await admin.post(`/api/admin/accounts/${await idOf('0911000001')}/reset-password`).send({ tempPassword: TEMP }).expect(400);
  });
});
