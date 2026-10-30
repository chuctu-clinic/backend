import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { app, registerUser, request, setup, teardown } from './helpers.js';
import { errorHandler } from '../src/middleware/error.js';

before(setup);
after(teardown);

/** Gọi trực tiếp bộ xử lý lỗi, trả về mã HTTP và nội dung phản hồi. */
function handle(err) {
  const out = {};
  const res = {
    status(code) {
      out.status = code;
      return this;
    },
    json(body) {
      out.body = body;
      return this;
    },
  };
  errorHandler(err, {}, res, () => {});
  return out;
}

describe('Phản hồi lỗi của API', () => {
  it('đường dẫn không tồn tại trả về 404 dạng JSON', async () => {
    const res = await request(app).get('/api/khong-co').expect(404);
    assert.equal(res.body.error.code, 'NOT_FOUND');
  });

  it('JSON sai cú pháp trả về 400', async () => {
    const res = await request(app).post('/api/auth/login').set('Content-Type', 'application/json').send('{sai').expect(400);
    assert.equal(res.body.error.code, 'BAD_JSON');
  });

  it('mã không đúng định dạng được coi là không tìm thấy', async () => {
    const user = await registerUser();
    const res = await user.get('/api/me/appointments/khong-phai-id').expect(404);
    assert.equal(res.body.error.code, 'NOT_FOUND');
  });

  it('trùng khoá duy nhất trả về 409', () => {
    const { status, body } = handle(Object.assign(new Error('E11000'), { code: 11000 }));
    assert.equal(status, 409);
    assert.equal(body.error.code, 'DUPLICATE');
  });

  it('lỗi không lường trước trả về 500 và không lộ chi tiết bên trong', () => {
    const { status, body } = handle(new Error('mongodb://user:pass@host mất kết nối'));
    assert.equal(status, 500);
    assert.equal(body.error.code, 'SERVER_ERROR');
    assert.doesNotMatch(JSON.stringify(body), /mongodb|pass/);
  });
});
