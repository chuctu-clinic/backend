import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { app, request, reset, setup, teardown } from './helpers.js';
import { User, VerificationCode } from '../src/models/index.js';

before(setup);
after(teardown);
beforeEach(reset);

const send = (email) => request(app).post('/api/auth/staff/forgot-password').send({ email });
const verify = (email, code) => request(app).post('/api/auth/staff/forgot-password/verify').send({ email, code });
const resetPw = (token, newPassword) => request(app).post('/api/auth/staff/reset-password').send({ token, newPassword });
// Lùi giờ các mã đã gửi 61 giây để bỏ qua thời gian chờ giữa hai lần gửi
const skipResendWait = () => VerificationCode.updateMany({}, [{ $set: { createdAt: { $subtract: ['$createdAt', 61_000] } } }]);
const login = (identifier, password) => request(app).post('/api/auth/staff/login').send({ identifier, password });

describe('Quên mật khẩu (nội bộ)', () => {
  it('nhận mã qua email, xác nhận mã rồi đặt mật khẩu mới', async () => {
    const sent = await send('LeTan@ChucTu.vn').expect(200);
    assert.equal(sent.body.email, 'letan@chuctu.vn');
    assert.match(sent.body.devCode, /^\d{6}$/);

    const ok = await verify('letan@chuctu.vn', sent.body.devCode).expect(200);
    assert.ok(ok.body.resetToken);

    await resetPw(ok.body.resetToken, 'MatKhauMoi456').expect(200);
    await login('letan@chuctu.vn', 'ChucTu@2026').expect(401);
    await login('letan@chuctu.vn', 'MatKhauMoi456').expect(200);
  });

  it('đặt lại xong thì bỏ yêu cầu đổi mật khẩu tạm', async () => {
    await User.updateOne({ email: 'letan@chuctu.vn' }, { mustChangePassword: true });
    const sent = await send('letan@chuctu.vn').expect(200);
    const ok = await verify('letan@chuctu.vn', sent.body.devCode).expect(200);
    await resetPw(ok.body.resetToken, 'MatKhauMoi456').expect(200);
    const res = await login('letan@chuctu.vn', 'MatKhauMoi456').expect(200);
    assert.equal(res.body.user.mustChangePassword, false);
  });

  it('email sai định dạng báo lỗi theo trường', async () => {
    const res = await send('khong-phai-email').expect(400);
    assert.ok(res.body.error.fields.email);
  });

  it('email không thuộc tài khoản nội bộ đang hoạt động: trả lời như thường nhưng không gửi mã', async () => {
    const res = await send('khongco@chuctu.vn').expect(200);
    assert.equal(res.body.devCode, undefined);
    await verify('khongco@chuctu.vn', '123456').expect(400);

    await User.updateOne({ email: 'letan@chuctu.vn' }, { isActive: false });
    const locked = await send('letan@chuctu.vn').expect(200);
    assert.equal(locked.body.devCode, undefined);
  });

  it('mã sai báo lỗi ở ô mã', async () => {
    const sent = await send('letan@chuctu.vn').expect(200);
    const wrong = sent.body.devCode === '000000' ? '111111' : '000000';
    const res = await verify('letan@chuctu.vn', wrong).expect(400);
    assert.ok(res.body.error.fields.code);
  });

  it('gửi lại mã phải chờ 60 giây', async () => {
    await send('letan@chuctu.vn').expect(200);
    await send('letan@chuctu.vn').expect(429);
  });

  it('mỗi email nhận tối đa 5 mã trong một giờ', async () => {
    for (let i = 0; i < 5; i += 1) {
      await send('letan@chuctu.vn').expect(200);
      await skipResendWait();
    }
    const res = await send('letan@chuctu.vn').expect(429);
    assert.match(res.body.error.message, /Email này đã nhận đủ 5 mã/);
  });

  it('mã gửi qua email không làm hết lượt mã SMS của số điện thoại', async () => {
    for (let i = 0; i < 3; i += 1) {
      await send('letan@chuctu.vn').expect(200);
      await skipResendWait();
    }
    const tieptan = await User.findOne({ email: 'letan@chuctu.vn' });
    const sms = await VerificationCode.countDocuments({ phone: tieptan.phone, email: null });
    assert.equal(sms, 0);
    assert.equal(await VerificationCode.countDocuments({ email: 'letan@chuctu.vn' }), 3);
  });

  it('mật khẩu mới phải từ 8 ký tự', async () => {
    const sent = await send('letan@chuctu.vn').expect(200);
    const ok = await verify('letan@chuctu.vn', sent.body.devCode).expect(200);
    const res = await resetPw(ok.body.resetToken, '1234567').expect(400);
    assert.ok(res.body.error.fields.newPassword);
  });

  it('mỗi lần xác nhận mã chỉ đặt lại được một lần', async () => {
    const sent = await send('letan@chuctu.vn').expect(200);
    const ok = await verify('letan@chuctu.vn', sent.body.devCode).expect(200);
    await resetPw(ok.body.resetToken, 'MatKhauMoi456').expect(200);
    await resetPw(ok.body.resetToken, 'MatKhauKhac789').expect(400);
    await login('letan@chuctu.vn', 'MatKhauMoi456').expect(200);
  });

  it('token đặt lại mật khẩu không dùng làm phiên đăng nhập được', async () => {
    const sent = await send('letan@chuctu.vn').expect(200);
    const ok = await verify('letan@chuctu.vn', sent.body.devCode).expect(200);
    const res = await request(app).get('/api/auth/me').set('Cookie', `ct_session=${ok.body.resetToken}`).expect(200);
    assert.equal(res.body.user, null);
  });
});
