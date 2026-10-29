import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, describe, it } from 'node:test';
import { app, request, reset, setup, teardown } from './helpers.js';
import { config } from '../src/config.js';
import { smtpTransport } from '../src/services/emailGateway.js';

before(setup);
after(teardown);
beforeEach(reset);

describe('Gửi mã qua SMTP', () => {
  let sent;
  let fail;
  const saved = { provider: config.emailProvider, from: config.mailFrom, create: smtpTransport.create };

  beforeEach(() => {
    sent = [];
    fail = false;
    config.emailProvider = 'smtp';
    config.mailFrom = 'Phòng khám Chúc Từ <phongkham@example.com>';
    smtpTransport.instance = null;
    smtpTransport.create = () => ({
      async sendMail(msg) {
        if (fail) throw new Error('Invalid login');
        sent.push(msg);
      },
    });
  });

  afterEach(() => {
    config.emailProvider = saved.provider;
    config.mailFrom = saved.from;
    smtpTransport.create = saved.create;
    smtpTransport.instance = null;
  });

  it('gửi email chứa mã tới đúng địa chỉ, không trả mã về giao diện', async () => {
    const res = await request(app).post('/api/auth/staff/forgot-password').send({ email: 'letan@chuctu.vn' }).expect(200);
    assert.equal(res.body.devCode, undefined);
    assert.equal(sent.length, 1);
    assert.equal(sent[0].to, 'letan@chuctu.vn');
    assert.equal(sent[0].from, config.mailFrom);
    assert.match(sent[0].subject, /đặt lại mật khẩu/);

    // Mã trong email dùng được để xác nhận
    const code = sent[0].text.match(/Mã xác nhận: (\d{6})/)[1];
    await request(app).post('/api/auth/staff/forgot-password/verify').send({ email: 'letan@chuctu.vn', code }).expect(200);
  });

  it('gửi không được thì báo lỗi rõ ràng và không tính lần xin mã', async () => {
    fail = true;
    const res = await request(app).post('/api/auth/staff/forgot-password').send({ email: 'letan@chuctu.vn' }).expect(502);
    assert.equal(res.body.error.code, 'EMAIL_FAILED');

    // Lần xin mã hỏng không bắt phải chờ 60 giây
    fail = false;
    await request(app).post('/api/auth/staff/forgot-password').send({ email: 'letan@chuctu.vn' }).expect(200);
    assert.equal(sent.length, 1);
  });
});
