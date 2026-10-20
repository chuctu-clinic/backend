import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { app, registerUser, request, reset, setup, staffAgent, teardown } from './helpers.ts';
import { VerificationCode } from '../src/models/index.ts';

before(setup);
after(teardown);
beforeEach(reset);

describe('Đăng ký và đăng nhập người dùng', () => {
  it('đăng ký bằng mã 6 số tạo tài khoản, hồ sơ chính chủ và đăng nhập luôn (BR.02)', async () => {
    const agent = await registerUser('0911000001');
    const me = await agent.get('/api/me').expect(200);
    assert.equal(me.body.user.phone, '0911000001');
    assert.match(me.body.patientCode, /^CT-BN-\d{2}-\d{4}$/);
  });

  it('mỗi số điện thoại chỉ một tài khoản (BR.01)', async () => {
    await registerUser('0911000001');
    const res = await request(app)
      .post('/api/auth/register')
      .send({ fullName: 'Người khác', phone: '0911000001', dob: '1970-01-01', gender: 'NU' })
      .expect(409);
    assert.equal(res.body.error.code, 'PHONE_TAKEN');
  });

  it('báo lỗi rõ ràng theo từng trường khi số điện thoại sai định dạng', async () => {
    const res = await request(app)
      .post('/api/auth/register')
      .send({ fullName: 'A B', phone: '12345', dob: '1970-01-01', gender: 'NU' })
      .expect(400);
    assert.match(res.body.error.fields.phone, /định dạng/);
  });

  it('mã sai thì không tạo tài khoản và báo số lần còn lại', async () => {
    const agent = request.agent(app);
    const res = await agent
      .post('/api/auth/register')
      .send({ fullName: 'A B', phone: '0911000002', dob: '1970-01-01', gender: 'NU' })
      .expect(200);
    const wrong = res.body.devCode === '000000' ? '111111' : '000000';
    const fail = await agent.post('/api/auth/register/verify').send({ phone: '0911000002', code: wrong }).expect(400);
    assert.equal(fail.body.error.code, 'CODE_WRONG');
    await agent.get('/api/me').expect(401);
  });

  it('gửi lại mã phải chờ 60 giây và tối đa 3 mã mỗi giờ (BR.03)', async () => {
    const phone = '0911000003';
    await request(app).post('/api/auth/register').send({ fullName: 'A B', phone, dob: '1970-01-01', gender: 'NU' }).expect(200);
    const tooSoon = await request(app).post('/api/auth/otp/resend').send({ phone, purpose: 'REGISTER' }).expect(429);
    assert.match(tooSoon.body.error.message, /chờ/);

    // Giả lập đã qua 60 giây giữa các lần gửi
    const backdate = async () => {
      const codes = await VerificationCode.find({ phone });
      for (const c of codes) {
        c.createdAt = new Date(c.createdAt.getTime() - 61_000);
        await c.save();
      }
    };
    await backdate();
    await request(app).post('/api/auth/otp/resend').send({ phone, purpose: 'REGISTER' }).expect(200);
    await backdate();
    await request(app).post('/api/auth/otp/resend').send({ phone, purpose: 'REGISTER' }).expect(200);
    await backdate();
    const limited = await request(app).post('/api/auth/otp/resend').send({ phone, purpose: 'REGISTER' }).expect(429);
    assert.match(limited.body.error.message, /3 mã/);
  });

  it('thiết bị đã xác nhận đăng nhập ngay, thiết bị mới phải nhập mã (BR.02)', async () => {
    const agent = await registerUser('0911000004');
    await agent.post('/api/auth/logout').expect(200);
    const sameDevice = await agent.post('/api/auth/login').send({ phone: '0911000004' }).expect(200);
    assert.equal(sameDevice.body.needCode, false);

    const newDevice = request.agent(app);
    const res = await newDevice.post('/api/auth/login').send({ phone: '0911000004' }).expect(200);
    assert.equal(res.body.needCode, true);
    await newDevice.post('/api/auth/login/verify').send({ phone: '0911000004', code: res.body.devCode }).expect(200);
    await newDevice.get('/api/me').expect(200);
  });

  it('người dùng không có quyền vào khu vực tiếp tân (QAs1)', async () => {
    const agent = await registerUser('0911000005');
    await agent.get('/api/staff/patients').expect(403);
    await agent.get('/api/admin/accounts').expect(403);
  });
});

describe('Tài khoản nội bộ', () => {
  it('tiếp tân đăng nhập bằng email hoặc số điện thoại và mật khẩu (BR.06)', async () => {
    await staffAgent('letan@chuctu.vn');
    await staffAgent('0900000002');
    await request(app).post('/api/auth/staff/login').send({ identifier: 'letan@chuctu.vn', password: 'sai-mat-khau' }).expect(401);
  });

  it('tiếp tân không dùng được chức năng của Admin (BR.05)', async () => {
    const agent = await staffAgent();
    await agent.get('/api/staff/queue').expect(200);
    await agent.get('/api/admin/dashboard').expect(403);
  });

  it('tài khoản bị khoá không đăng nhập được và phiên cũ hết hiệu lực ngay (BR.33)', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const user = await registerUser('0911000006');
    const list = await admin.get('/api/admin/accounts?q=0911000006').expect(200);
    await admin.post(`/api/admin/accounts/${list.body.items[0].id}/lock`).expect(200);
    await user.get('/api/me').expect(401);
    const res = await user.post('/api/auth/login').send({ phone: '0911000006' }).expect(403);
    assert.match(res.body.error.message, /khoá/);
  });

  it('tài khoản nội bộ mới phải đổi mật khẩu tạm trước khi làm việc', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    await admin
      .post('/api/admin/accounts')
      .send({ fullName: 'Lễ Tân Mới', phone: '0900000099', role: 'RECEPTIONIST', tempPassword: 'TamThoi123' })
      .expect(201);
    const agent = request.agent(app);
    await agent.post('/api/auth/staff/login').send({ identifier: '0900000099', password: 'TamThoi123' }).expect(200);
    await agent.get('/api/staff/queue').expect(403);
    await agent.post('/api/auth/change-password').send({ currentPassword: 'TamThoi123', newPassword: 'MatKhauMoi456' }).expect(200);
    await agent.get('/api/staff/queue').expect(200);
  });

  it('mật khẩu tạm phải từ 8 ký tự', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const res = await admin
      .post('/api/admin/accounts')
      .send({ fullName: 'Lễ Tân', phone: '0900000098', role: 'RECEPTIONIST', tempPassword: '123' })
      .expect(400);
    assert.ok(res.body.error.fields.tempPassword);
  });
});
