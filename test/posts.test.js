import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { app, request, reset, setup, staffAgent, teardown } from './helpers.js';

before(setup);
after(teardown);
beforeEach(reset);

// Ảnh PNG 1×1 điểm ảnh
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

describe('Bài viết và ảnh (UC.5.1 – UC.5.5)', () => {
  it('Admin tải ảnh lên, ai cũng xem được ảnh qua đường dẫn trả về', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const res = await admin.post('/api/admin/uploads').set('Content-Type', 'image/png').send(PNG).expect(201);
    assert.match(res.body.url, /^\/api\/uploads\/[a-f\d]{24}$/);
    const img = await request(app).get(res.body.url).expect(200);
    assert.equal(img.headers['content-type'], 'image/png');
    assert.equal(img.body.length, PNG.length);
    await admin.post('/api/admin/uploads').set('Content-Type', 'text/plain').send('xin chao').expect(400);
  });

  it('tiếp tân không tải ảnh được', async () => {
    const staff = await staffAgent();
    await staff.post('/api/admin/uploads').set('Content-Type', 'image/png').send(PNG).expect(403);
  });

  it('ẩn bài viết thì bài rời website, công khai lại thì hiện trở lại', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const up = await admin.post('/api/admin/uploads').set('Content-Type', 'image/png').send(PNG).expect(201);
    const created = await admin
      .post('/api/admin/posts')
      .send({ title: 'Lịch khám dịp lễ', category: 'TIN_TUC', authorName: 'Phòng khám Chúc Từ', thumbnailUrl: up.body.url, content: '<p>Nội dung</p>', status: 'PUBLISHED' })
      .expect(201);
    const id = created.body.post.id;
    await request(app).get(`/api/posts/${id}`).expect(200);

    const hidden = await admin.put(`/api/admin/posts/${id}`).send({ status: 'HIDDEN' }).expect(200);
    assert.equal(hidden.body.post.status, 'HIDDEN');
    assert.ok(hidden.body.post.publishedAt);
    await request(app).get(`/api/posts/${id}`).expect(404);
    const list = (await admin.get('/api/admin/posts?status=HIDDEN').expect(200)).body;
    assert.equal(list.items.length, 1);
    assert.equal(list.statusCounts.HIDDEN, 1);

    await admin.put(`/api/admin/posts/${id}`).send({ status: 'PUBLISHED' }).expect(200);
    await request(app).get(`/api/posts/${id}`).expect(200);
  });

  it('bản nháp giữ ngày đăng đã chọn để dùng khi công khai', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const publishedAt = '2026-11-02T01:00:00.000Z';
    const created = await admin.post('/api/admin/posts').send({ title: 'Bài nháp', category: 'TIN_TUC', publishedAt, status: 'DRAFT' }).expect(201);
    assert.equal(created.body.post.publishedAt, publishedAt);
    const done = await admin.put(`/api/admin/posts/${created.body.post.id}`).send({ content: 'Nội dung', authorName: 'BS', status: 'PUBLISHED' }).expect(200);
    assert.equal(done.body.post.publishedAt, publishedAt);
  });
});
