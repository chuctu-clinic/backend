import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { app, registerUser, request, reset, setup, staffAgent, teardown } from './helpers.js';

before(setup);
after(teardown);
beforeEach(reset);

describe('Nhân viên đổi email (xác minh bằng mã gửi tới email mới)', () => {
  it('gửi mã tới email mới, nhập đúng mã thì lưu email', async () => {
    const agent = await staffAgent('letan@chuctu.vn');
    const sent = await agent.post('/api/staff/account/email').send({ email: 'Thu.Tran@Email.com' }).expect(200);
    assert.equal(sent.body.email, 'thu.tran@email.com');
    assert.match(sent.body.devCode, /^\d{6}$/);

    // Chưa xác minh thì email chưa đổi
    assert.equal((await agent.get('/api/auth/me').expect(200)).body.user.email, 'letan@chuctu.vn');

    const res = await agent.post('/api/staff/account/email/verify').send({ code: sent.body.devCode }).expect(200);
    assert.equal(res.body.user.email, 'thu.tran@email.com');
    await request(app).post('/api/auth/staff/login').send({ identifier: 'thu.tran@email.com', password: 'ChucTu@2026' }).expect(200);
  });

  it('mã sai thì không đổi email', async () => {
    const agent = await staffAgent();
    const sent = await agent.post('/api/staff/account/email').send({ email: 'moi@email.com' }).expect(200);
    const wrong = sent.body.devCode === '000000' ? '111111' : '000000';
    const res = await agent.post('/api/staff/account/email/verify').send({ code: wrong }).expect(400);
    assert.ok(res.body.error.fields.code);
    assert.equal((await agent.get('/api/auth/me').expect(200)).body.user.email, 'letan@chuctu.vn');
  });

  it('email sai định dạng, trùng email cũ hoặc đã thuộc tài khoản khác thì báo lỗi ở ô email', async () => {
    const agent = await staffAgent('letan@chuctu.vn');
    let res = await agent.post('/api/staff/account/email').send({ email: 'khong-phai-email' }).expect(400);
    assert.ok(res.body.error.fields.email);
    res = await agent.post('/api/staff/account/email').send({ email: 'letan@chuctu.vn' }).expect(400);
    assert.ok(res.body.error.fields.email);
    res = await agent.post('/api/staff/account/email').send({ email: 'admin@chuctu.vn' }).expect(409);
    assert.ok(res.body.error.fields.email);
  });

  it('chưa xin mã thì không xác minh được', async () => {
    const agent = await staffAgent();
    await agent.post('/api/staff/account/email/verify').send({ code: '123456' }).expect(400);
  });

  it('người dùng không dùng được API này', async () => {
    const user = await registerUser('0911000009');
    await user.post('/api/staff/account/email').send({ email: 'a@b.com' }).expect(403);
  });
});
