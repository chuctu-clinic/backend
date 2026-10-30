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

describe('Thứ tự và phân trang ở trang quản trị', () => {
  const titles = async (admin, qs) => (await admin.get(`/api/admin/posts?limit=10${qs}`).expect(200)).body.items.map((p) => p.title);

  it('mỗi trang tối đa 10 bài, bài mới thêm đứng đầu trang 1', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const added = [];
    for (let i = 1; i <= 12; i += 1) added.push((await create(admin, { title: `Bài ${i}` })).title);

    const first = await titles(admin, '&page=1');
    const second = await titles(admin, '&page=2');
    assert.equal(first.length, 10);
    assert.deepEqual(first[0], 'Bài 12');
    assert.deepEqual([...first, ...second].filter((t) => added.includes(t)).sort(), [...added].sort());

    await create(admin, { title: 'Bài vừa thêm', status: 'DRAFT' });
    assert.equal((await titles(admin, '&page=1'))[0], 'Bài vừa thêm');
  });

  it('sửa, ẩn hoặc ghim bài thì bài không đổi chỗ', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const old = await create(admin, { title: 'Bài cũ' });
    await create(admin, { title: 'Bài mới' });

    await admin.put(`/api/admin/posts/${old.id}`).send({ title: 'Bài cũ đã sửa' }).expect(200);
    await admin.put(`/api/admin/posts/${old.id}`).send({ status: 'HIDDEN' }).expect(200);
    await admin.put(`/api/admin/posts/${old.id}`).send({ status: 'PUBLISHED' }).expect(200);
    await admin.post(`/api/admin/posts/${old.id}/pin`).expect(200);
    assert.deepEqual((await titles(admin, '&page=1')).slice(0, 2), ['Bài mới', 'Bài cũ đã sửa']);
  });
});

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

describe('Ghim bài và bài nổi bật ở trang Bài viết', () => {
  const featuredList = async (qs = '') => (await request(app).get(`/api/posts?featured=1${qs}`).expect(200)).body;
  const seed = async (admin, count) => {
    const posts = [];
    for (let i = 1; i <= count; i += 1) {
      posts.push(await create(admin, { title: `Bài ${i}`, publishedAt: new Date(Date.UTC(2026, 0, i)).toISOString() }));
    }
    return posts;
  };

  it('bài ghim đứng yên ở mọi trang, các trang chỉ chia 9 bài còn lại', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const posts = await seed(admin, 12);
    await admin.post(`/api/admin/posts/${posts[0].id}/pin`).expect(200);

    const first = await featuredList('&page=1&limit=9');
    const second = await featuredList('&page=2&limit=9');
    assert.equal(first.featured.id, posts[0].id);
    assert.equal(second.featured.id, posts[0].id);
    assert.equal(first.total, 12);
    assert.equal(first.items.length, 9);
    assert.equal(second.items.length, 2);
    const shown = [...first.items, ...second.items].map((p) => p.id);
    assert.equal(new Set(shown).size, 11);
    assert.ok(!shown.includes(posts[0].id));
  });

  it('chưa ghim thì bài mới nhất làm bài nổi bật', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const posts = await seed(admin, 3);
    const list = await featuredList();
    assert.equal(list.featured.id, posts[2].id);
    assert.deepEqual(list.items.map((p) => p.id), [posts[1].id, posts[0].id]);
  });

  it('bỏ ghim thì bài mới nhất trở lại, kể cả khi có bài cũ chưa từng ghim', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const posts = await seed(admin, 3);
    await admin.post(`/api/admin/posts/${posts[0].id}/pin`).expect(200);
    const { post } = (await admin.post(`/api/admin/posts/${posts[0].id}/unpin`).expect(200)).body;
    assert.equal(post.pinned, false);
    assert.equal((await featuredList()).featured.id, posts[2].id);
  });

  it('mỗi lần chỉ ghim một bài: ghim bài mới thì bài cũ tự bỏ ghim', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const [a, b] = await seed(admin, 2);
    await admin.post(`/api/admin/posts/${a.id}/pin`).expect(200);
    await admin.post(`/api/admin/posts/${a.id}/pin`).expect(200);
    await admin.post(`/api/admin/posts/${b.id}/pin`).expect(200);

    const { items } = (await admin.get('/api/admin/posts?limit=50').expect(200)).body;
    assert.deepEqual(items.filter((p) => p.pinned).map((p) => p.id), [b.id]);
  });

  it('lọc chuyên mục khác thì bài nổi bật là bài mới nhất của chuyên mục đó', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const [pinned] = await seed(admin, 2);
    const guide = await create(admin, { title: 'Hướng dẫn', category: 'HUONG_DAN' });
    await admin.post(`/api/admin/posts/${pinned.id}/pin`).expect(200);

    assert.equal((await featuredList('&category=HUONG_DAN')).featured.id, guide.id);
    assert.equal((await featuredList('&category=TIN_TUC')).featured.id, pinned.id);
    assert.equal((await featuredList('&category=SU_KIEN')).featured, null);
  });

  it('ghim không đổi giờ cập nhật', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const [post] = await seed(admin, 1);
    const pinned = (await admin.post(`/api/admin/posts/${post.id}/pin`).expect(200)).body.post;
    assert.equal(pinned.updatedAt, post.updatedAt);
  });

  it('ẩn bài đang ghim thì mất ghim, công khai lại không tự ghim', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const [a, b] = await seed(admin, 2);
    await admin.post(`/api/admin/posts/${a.id}/pin`).expect(200);

    const hidden = (await admin.put(`/api/admin/posts/${a.id}`).send({ status: 'HIDDEN' }).expect(200)).body.post;
    assert.equal(hidden.pinned, false);
    await admin.put(`/api/admin/posts/${a.id}`).send({ status: 'PUBLISHED' }).expect(200);
    assert.equal((await featuredList()).featured.id, b.id);
  });

  it('chỉ ghim được bài đã công khai, không ghim trang cố định, tiếp tân không ghim được', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const staff = await staffAgent();
    const draft = await create(admin, { title: 'Bản nháp', status: 'DRAFT' });
    const page = (await request(app).get('/api/posts/gioi-thieu').expect(200)).body.post;
    const [post] = await seed(admin, 1);

    const res = await admin.post(`/api/admin/posts/${draft.id}/pin`).expect(400);
    assert.equal(res.body.error.code, 'NOT_PINNABLE');
    await admin.post(`/api/admin/posts/${page.id}/pin`).expect(400);
    await staff.post(`/api/admin/posts/${post.id}/pin`).expect(403);
    await admin.post('/api/admin/posts/000000000000000000000000/pin').expect(404);
  });

  it('danh sách thường không tách bài nổi bật (trang chủ dùng)', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const posts = await seed(admin, 3);
    await admin.post(`/api/admin/posts/${posts[0].id}/pin`).expect(200);
    const list = (await request(app).get('/api/posts?limit=3').expect(200)).body;
    assert.equal(list.featured, null);
    assert.deepEqual(list.items.map((p) => p.id), [posts[2].id, posts[1].id, posts[0].id]);
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
