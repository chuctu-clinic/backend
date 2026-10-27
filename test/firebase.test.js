import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, describe, it } from 'node:test';
import { app, request, reset, setup, teardown } from './helpers.js';

const { config } = await import('../src/config.js');
const { firebaseVerifier } = await import('../src/services/smsGateway.js');

before(setup);
after(teardown);

// Chế độ Firebase: trình duyệt gửi và xác nhận mã, server kiểm ID token. Ở đây giả lập phần kiểm token.
const realVerify = firebaseVerifier.verify;
let tokenPhone = null;
let tokenAge = 0;
beforeEach(async () => {
  await reset();
  config.smsProvider = 'firebase';
  config.firebase.projectId = 'chuc-tu-test';
  tokenPhone = null;
  tokenAge = 0;
  firebaseVerifier.verify = async (idToken) => {
    if (idToken !== 'token-hop-le-cua-firebase') throw new Error('invalid');
    return { phone: tokenPhone, authTime: Date.now() - tokenAge };
  };
});
afterEach(() => {
  config.smsProvider = 'console';
  firebaseVerifier.verify = realVerify;
});

const TOKEN = 'token-hop-le-cua-firebase';
const form = { fullName: 'Lê Thị Firebase', phone: '0911000077', dob: '1960-02-03', gender: 'NU' };

describe('Xác thực số điện thoại bằng Firebase', () => {
  it('giao diện biết phải gửi mã qua Firebase', async () => {
    const meta = (await request(app).get('/api/meta').expect(200)).body;
    assert.equal(meta.otp.provider, 'firebase');
    assert.equal(meta.otp.firebase.projectId, 'chuc-tu-test');
  });

  it('đăng ký: server không sinh mã, nhận ID token đúng số thì tạo tài khoản; không dùng lại được', async () => {
    const agent = request.agent(app);
    const start = (await agent.post('/api/auth/register').send(form).expect(200)).body;
    assert.equal(start.provider, 'firebase');
    assert.equal(start.devCode, undefined);

    tokenPhone = '+84911000077';
    const done = await agent.post('/api/auth/register/verify').send({ phone: form.phone, idToken: TOKEN }).expect(201);
    assert.equal(done.body.user.fullName, form.fullName);
    await agent.get('/api/me').expect(200);
  });

  it('token của số khác, token sai, token cũ đều bị từ chối', async () => {
    await request(app).post('/api/auth/register').send(form).expect(200);
    tokenPhone = '+84911999999';
    let res = await request(app).post('/api/auth/register/verify').send({ phone: form.phone, idToken: TOKEN }).expect(400);
    assert.equal(res.body.error.code, 'PHONE_MISMATCH');

    res = await request(app).post('/api/auth/register/verify').send({ phone: form.phone, idToken: 'token-gia-mao-khong-hop-le' }).expect(400);
    assert.equal(res.body.error.code, 'TOKEN_INVALID');

    tokenPhone = '+84911000077';
    tokenAge = 20 * 60_000;
    res = await request(app).post('/api/auth/register/verify').send({ phone: form.phone, idToken: TOKEN }).expect(400);
    assert.equal(res.body.error.code, 'CODE_EXPIRED');
  });

  it('mỗi lần xin mã chỉ đổi được một phiên đăng nhập', async () => {
    const agent = request.agent(app);
    await agent.post('/api/auth/register').send(form).expect(200);
    tokenPhone = '+84911000077';
    await agent.post('/api/auth/register/verify').send({ phone: form.phone, idToken: TOKEN }).expect(201);

    // Máy mới: xin mã một lần, xác nhận được một lần
    const other = request.agent(app);
    const start = (await other.post('/api/auth/login').send({ phone: form.phone }).expect(200)).body;
    assert.equal(start.needCode, true);
    assert.equal(start.provider, 'firebase');
    await other.post('/api/auth/login/verify').send({ phone: form.phone, idToken: TOKEN }).expect(200);
    const again = await request(app).post('/api/auth/login/verify').send({ phone: form.phone, idToken: TOKEN }).expect(400);
    assert.equal(again.body.error.code, 'CODE_EXPIRED');
  });
});
