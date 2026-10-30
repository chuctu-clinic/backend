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
      .send({ title: 'Lịch khám dịp lễ', summary: 'Giờ khám các ngày lễ.', category: 'TIN_TUC', authorName: 'Phòng khám Chúc Từ', thumbnailUrl: up.body.url, content: '<p>Nội dung</p>', status: 'PUBLISHED' })
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
    const done = await admin.put(`/api/admin/posts/${created.body.post.id}`).send({ content: 'Nội dung', summary: 'Mô tả bài', authorName: 'BS', status: 'PUBLISHED' }).expect(200);
    assert.equal(done.body.post.publishedAt, publishedAt);
  });

  it('công khai bài phải có mô tả; bản nháp thì chưa cần', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const draft = await admin.post('/api/admin/posts').send({ title: 'Chưa có mô tả', category: 'TIN_TUC', status: 'DRAFT' }).expect(201);
    const res = await admin
      .put(`/api/admin/posts/${draft.body.post.id}`)
      .send({ content: '<p>Nội dung</p>', authorName: 'BS', status: 'PUBLISHED' })
      .expect(400);
    assert.equal(res.body.error.fields.summary, 'Bài viết cần có mô tả.');
    await admin.put(`/api/admin/posts/${draft.body.post.id}`).send({ summary: '   ', status: 'PUBLISHED' }).expect(400);
  });
});

describe('Danh sách bài viết của quản trị', () => {
  it('không hiện trang thông tin cố định, chỉ 4 danh mục bài viết như bên website', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const list = (await admin.get('/api/admin/posts?limit=50').expect(200)).body;
    assert.ok(list.items.every((p) => p.category !== 'TRANG' && !p.slug));
    const counted = list.statusCounts.PUBLISHED + list.statusCounts.DRAFT + list.statusCounts.HIDDEN;
    assert.equal(counted, list.total);
    // Lọc theo TRANG cũng không lộ ra trang thông tin
    const pages = (await admin.get('/api/admin/posts?category=TRANG').expect(200)).body;
    assert.ok(pages.items.every((p) => p.category !== 'TRANG'));
    // Trang thông tin vẫn còn cho trang Giới thiệu… dùng
    await request(app).get('/api/posts/gioi-thieu').expect(200);
  });
});

const POST = { summary: 'Mô tả bài', category: 'TIN_TUC', authorName: 'BS', content: '<p>Nội dung</p>', status: 'PUBLISHED' };
const create = async (admin, data) => (await admin.post('/api/admin/posts').send({ ...POST, ...data }).expect(201)).body.post;

describe('Bài viết công khai (BR.08, UC.4.1 – UC.4.3)', () => {
  const publicList = async (qs = '') => (await request(app).get(`/api/posts${qs}`).expect(200)).body;

  it('chỉ hiện bài đã công khai, không có bản nháp, bài ẩn và trang cố định', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const shown = await create(admin, { title: 'Bài công khai' });
    const draft = await create(admin, { title: 'Bản nháp', status: 'DRAFT' });
    const hidden = await create(admin, { title: 'Bài ẩn', status: 'HIDDEN' });

    const list = await publicList();
    assert.deepEqual(list.items.map((p) => p.id), [shown.id]);
    assert.equal(list.total, 1);
    assert.equal(list.items[0].content, undefined);
    await request(app).get(`/api/posts/${draft.id}`).expect(404);
    await request(app).get(`/api/posts/${hidden.id}`).expect(404);
  });

  it('lọc theo danh mục, tìm theo tiêu đề, bài mới đăng lên trước', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const older = await create(admin, { title: 'Khám miễn phí tháng 10', publishedAt: '2026-10-01T01:00:00.000Z' });
    const newer = await create(admin, { title: 'Khám miễn phí tháng 11', publishedAt: '2026-11-01T01:00:00.000Z' });
    const guide = await create(admin, { title: 'Sắc thuốc đúng cách', category: 'HUONG_DAN' });

    assert.deepEqual((await publicList('?category=HUONG_DAN')).items.map((p) => p.id), [guide.id]);
    assert.deepEqual((await publicList(`?q=${encodeURIComponent('miễn phí')}`)).items.map((p) => p.id), [newer.id, older.id]);
    // Ký tự đặc biệt trong ô tìm kiếm không được làm hỏng truy vấn
    assert.deepEqual((await publicList(`?q=${encodeURIComponent('(*')}`)).items, []);
  });
});

describe('Xoá bài viết (UC.5.5)', () => {
  it('xoá xong bài rời website và không khôi phục được', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const post = await create(admin, { title: 'Bài sẽ xoá' });
    await admin.delete(`/api/admin/posts/${post.id}`).expect(200);
    await request(app).get(`/api/posts/${post.id}`).expect(404);
    await admin.get(`/api/admin/posts/${post.id}`).expect(404);
    await admin.delete(`/api/admin/posts/${post.id}`).expect(404);
  });

  it('không xoá được trang thông tin cố định', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const page = (await request(app).get('/api/posts/gioi-thieu').expect(200)).body.post;
    await admin.delete(`/api/admin/posts/${page.id}`).expect(400);
    await request(app).get('/api/posts/gioi-thieu').expect(200);
  });

  it('tiếp tân không xoá và không sửa được bài', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const staff = await staffAgent();
    const post = await create(admin, { title: 'Bài của Admin' });
    await staff.delete(`/api/admin/posts/${post.id}`).expect(403);
    await staff.put(`/api/admin/posts/${post.id}`).send({ status: 'HIDDEN' }).expect(403);
  });
});
