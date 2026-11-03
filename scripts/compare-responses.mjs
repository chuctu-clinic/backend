// Chạy cùng một kịch bản trên bản Node và bản Java, so từng phản hồi (mã HTTP, cấu trúc JSON, kiểu và giá trị).
//
//   node scripts/compare-responses.mjs [--node http://127.0.0.1:4100] [--java http://127.0.0.1:4200] [--verbose]
//
// An toàn: chỉ nhận địa chỉ 127.0.0.1 (không dùng localhost để khỏi đụng cookie ct_session của người dùng trên localhost).
// Bản Node phải chạy chế độ demo (`node src/index.js --demo`), bản Java dùng profile demo, cả hai với
// SMS_PROVIDER=console và EMAIL_PROVIDER=console. Mỗi kịch bản dùng một kho cookie riêng.
//
// Quy tắc bỏ qua (dữ liệu demo khác nhau đôi chút vì bộ sinh số giả ngẫu nhiên, và vì ID/thời điểm vốn khác nhau):
//  1. Khoá `id` hoặc kết thúc bằng `Id`/`Ids` có giá trị chuỗi: chỉ kiểm là chuỗi (ID Mongo và ID MySQL khác dạng).
//  2. `ticketCode`, `patientCode`, `devCode`: chỉ so khuôn (chữ số đổi thành 9); `resetToken`, `token`: chỉ kiểm là chuỗi;
//     đường dẫn ảnh /api/uploads/<id>: id bị che.
//  3. Chuỗi thời điểm ISO 3 chữ số mili giây có Z: chỉ kiểm đúng định dạng, trừ `dob` (luôn 00:00:00.000Z) so nguyên văn.
//  3b. Ở bước `mode: 'shape'`, `dob` cũng chỉ kiểm định dạng (ngày sinh của dữ liệu demo là số ngẫu nhiên).
//  4. Số giây/phút chờ phụ thuộc giờ chạy (`retryAfter`, `waitMinutes`, `etaMinutes`...): chỉ kiểm là số.
//  5. Bước đặt `mode: 'shape'` (danh sách lấy từ dữ liệu demo như lịch trong ngày, hàng đợi, bảng điều khiển): chỉ so khoá,
//     kiểu giá trị và việc mảng rỗng hay không; không so giá trị và độ dài mảng.
//  6. Bước đặt `ignore: [tên khoá, ...]`: bỏ các khoá đó (hiện không bước nào dùng).
//  7. Set-Cookie: so tên cookie, HttpOnly, SameSite, Path, Secure, có Max-Age hay không và giá trị Max-Age; không so giá trị cookie
//     và Expires.
//  7b. Cookie `ct_staff` (cookie cũ của bản trước): bỏ qua, spec bỏ nó nên Java không đặt hay xoá.
//  8. Header: chỉ so Content-Type (loại, bỏ khoảng trắng và hoa thường của charset) và Cache-Control.
//  8b. Mục [header]: header bảo mật và CORS (helmet, cors) so từng tên; Vary so như một tập.
//  9. Phản hồi không phải JSON (ảnh): so Content-Type và byte.
import { createHash, randomInt } from 'node:crypto';

const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : def;
};
const VERBOSE = args.includes('--verbose');
// --show <đoạn nhãn>: in phản hồi đầy đủ của các bước có nhãn chứa đoạn đó (để soi khi gỡ lỗi kịch bản)
const SHOW = opt('show', null);
const SERVERS = { node: opt('node', 'http://127.0.0.1:4100'), java: opt('java', 'http://127.0.0.1:4200') };
for (const [k, u] of Object.entries(SERVERS)) {
  if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(u)) {
    console.error(`Địa chỉ ${k} phải dạng http://127.0.0.1:<cổng>, nhận được ${u}`);
    process.exit(2);
  }
}

// ---------------------------------------------------------------- HTTP có kho cookie

class Jar {
  constructor() { this.cookies = new Map(); }
  header() { return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; '); }
  absorb(setCookies) {
    for (const sc of setCookies) {
      const [pair, ...attrs] = sc.split(';').map((s) => s.trim());
      const eq = pair.indexOf('=');
      const name = pair.slice(0, eq);
      const value = pair.slice(eq + 1);
      const maxAge = attrs.find((a) => /^max-age=/i.test(a));
      const expired = (maxAge && Number(maxAge.split('=')[1]) <= 0) || value === '';
      if (expired) this.cookies.delete(name);
      else this.cookies.set(name, value);
    }
  }
}

async function call(server, jar, { method, path, body, raw, contentType, headers = {} }) {
  const h = { ...headers };
  const cookie = jar.header();
  if (cookie) h.Cookie = cookie;
  let payload;
  if (raw !== undefined) {
    payload = raw;
    h['Content-Type'] = contentType;
  } else if (body !== undefined) {
    payload = typeof body === 'string' ? body : JSON.stringify(body);
    h['Content-Type'] = contentType ?? 'application/json';
  }
  const res = await fetch(SERVERS[server] + path, { method, headers: h, body: payload, redirect: 'manual' });
  const setCookies = res.headers.getSetCookie();
  jar.absorb(setCookies);
  const buf = Buffer.from(await res.arrayBuffer());
  const ct = res.headers.get('content-type') ?? '';
  let json;
  if (/json/i.test(ct)) {
    try { json = JSON.parse(buf.toString('utf8')); } catch { json = { __invalidJson: buf.toString('utf8').slice(0, 200) }; }
  }
  return { status: res.status, ct, cacheControl: res.headers.get('cache-control'), setCookies, json, buf };
}

// ---------------------------------------------------------------- Chuẩn hoá và so sánh

const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
const MASK_KEYS = new Set(['ticketCode', 'patientCode', 'devCode']);
const TOKEN_KEYS = new Set(['resetToken', 'token']);
const NUMBER_ONLY_KEYS = new Set(['retryAfter', 'waitMinutes', 'etaMinutes', 'waitingMinutes', 'minutesWaiting', 'waitedMinutes']);
const isIdKey = (k) => k === 'id' || /Ids?$/.test(k);

function norm(v, key, o) {
  if (v === null) return null;
  if (Array.isArray(v)) {
    if (o.shape) return v.length ? [norm(v[0], key, o), '…'] : [];
    return v.map((x) => norm(x, key, o));
  }
  if (typeof v === 'object') {
    const out = {};
    for (const [k, x] of Object.entries(v)) {
      if (o.ignore?.includes(k)) continue;
      out[k] = norm(x, k, o);
    }
    return out;
  }
  if (key && isIdKey(key)) return typeof v === 'string' ? '<id>' : `<${typeof v} (phải là chuỗi)>`;
  if (key && NUMBER_ONLY_KEYS.has(key)) return typeof v === 'number' ? '<number>' : `<${typeof v}>`;
  if (typeof v === 'string') {
    if (key && TOKEN_KEYS.has(key)) return '<token>';
    if (key && MASK_KEYS.has(key)) return v.replace(/\d/g, '9');
    if (/^\/api\/uploads\/([a-f\d]{24}|\d+)$/.test(v)) return '/api/uploads/<id>';
    if (INSTANT.test(v)) return (key === 'dob' && !o.shape) || o.exactInstants?.includes(key) ? v : '<instant>';
    if (/^\d{4}-\d{2}-\d{2}T/.test(v)) return `<instant sai định dạng: ${v}>`;
    return o.shape ? '<string>' : v;
  }
  if (o.shape) return `<${typeof v}>`;
  return v;
}

function diff(a, b, path, out) {
  if (JSON.stringify(a) === JSON.stringify(b)) return;
  const ta = Array.isArray(a) ? 'array' : a === null ? 'null' : typeof a;
  const tb = Array.isArray(b) ? 'array' : b === null ? 'null' : typeof b;
  if (ta !== tb) { out.push(`${path || '$'}: node=${JSON.stringify(a)} java=${JSON.stringify(b)}`); return; }
  if (ta === 'array') {
    if (a.length !== b.length) out.push(`${path || '$'}: độ dài mảng node=${a.length} java=${b.length}`);
    for (let i = 0; i < Math.min(a.length, b.length); i++) diff(a[i], b[i], `${path}[${i}]`, out);
    return;
  }
  if (ta === 'object') {
    for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) {
      if (!(k in a)) out.push(`${path}.${k}: chỉ có ở java = ${JSON.stringify(b[k])}`);
      else if (!(k in b)) out.push(`${path}.${k}: chỉ có ở node = ${JSON.stringify(a[k])}`);
      else diff(a[k], b[k], `${path}.${k}`, out);
    }
    return;
  }
  out.push(`${path || '$'}: node=${JSON.stringify(a)} java=${JSON.stringify(b)}`);
}

function cookieShape(sc) {
  const [pair, ...attrs] = sc.split(';').map((s) => s.trim());
  const name = pair.slice(0, pair.indexOf('='));
  const value = pair.slice(pair.indexOf('=') + 1);
  const m = {};
  for (const a of attrs) {
    const [k, ...r] = a.split('=');
    m[k.toLowerCase()] = r.join('=');
  }
  const cleared = value === '' || (m['max-age'] !== undefined && Number(m['max-age']) <= 0);
  return `${name} cleared=${cleared} httponly=${'httponly' in m} secure=${'secure' in m} samesite=${(m.samesite ?? '-').toLowerCase()} path=${m.path ?? '-'} maxAge=${cleared ? '-' : (m['max-age'] ?? '-')} expires=${cleared ? '-' : 'expires' in m}`;
}

const ctType = (ct) => ct.split(';')[0].trim().toLowerCase();

function compare(step, rn, rj) {
  const d = [];
  if (rn.status !== rj.status) d.push(`HTTP status: node=${rn.status} java=${rj.status}`);
  if (ctType(rn.ct) !== ctType(rj.ct)) d.push(`Content-Type: node=${rn.ct} java=${rj.ct}`);
  if ((rn.cacheControl ?? null) !== (rj.cacheControl ?? null)) d.push(`Cache-Control: node=${rn.cacheControl} java=${rj.cacheControl}`);
  // ct_staff: spec bỏ cookie cũ này nên Java không xoá nó khi đăng xuất
  const cn = rn.setCookies.filter((c) => !c.startsWith('ct_staff=')).map(cookieShape).sort();
  const cj = rj.setCookies.filter((c) => !c.startsWith('ct_staff=')).map(cookieShape).sort();
  if (JSON.stringify(cn) !== JSON.stringify(cj)) d.push(`Set-Cookie: node=${JSON.stringify(cn)} java=${JSON.stringify(cj)}`);
  if (rn.json !== undefined || rj.json !== undefined) {
    diff(norm(rn.json, null, step), norm(rj.json, null, step), '', d);
  } else {
    const hn = createHash('sha256').update(rn.buf).digest('hex');
    const hj = createHash('sha256').update(rj.buf).digest('hex');
    if (hn !== hj) d.push(`nội dung không phải JSON khác nhau: node=${rn.buf.length}B java=${rj.buf.length}B`);
  }
  if (step.expectStatus !== undefined && (rn.status !== step.expectStatus || rj.status !== step.expectStatus)) {
    d.push(`kỳ vọng HTTP ${step.expectStatus}, node=${rn.status} java=${rj.status}`);
  }
  return d;
}

// ---------------------------------------------------------------- Kịch bản

const results = { steps: 0, diffs: 0, failed: [] };

// Header bảo mật và CORS (Node dùng helmet + cors): so tên và giá trị; Vary so như tập.
const HEADER_NAMES = [
  'access-control-allow-origin', 'access-control-allow-credentials', 'access-control-allow-methods', 'access-control-allow-headers',
  'access-control-max-age', 'vary', 'cross-origin-opener-policy', 'cross-origin-resource-policy', 'origin-agent-cluster',
  'referrer-policy', 'strict-transport-security', 'x-content-type-options', 'x-dns-prefetch-control', 'x-download-options',
  'x-frame-options', 'x-permitted-cross-domain-policies', 'x-xss-protection', 'content-security-policy',
];
async function headerCheck(label, method, path, reqHeaders) {
  const got = {};
  for (const server of ['node', 'java']) {
    const res = await fetch(SERVERS[server] + path, { method, headers: reqHeaders, redirect: 'manual' });
    await res.arrayBuffer();
    const h = {};
    for (const n of HEADER_NAMES) {
      const v = res.headers.get(n);
      if (v !== null) h[n] = n === 'vary' ? v.split(',').map((x) => x.trim().toLowerCase()).sort().join(', ') : v;
    }
    got[server] = { status: res.status, h };
  }
  const d = [];
  if (got.node.status !== got.java.status) d.push(`HTTP status: node=${got.node.status} java=${got.java.status}`);
  diff(got.node.h, got.java.h, '', d);
  results.steps++;
  const tag = `[header] ${label} (${method} ${path})`;
  if (d.length) {
    results.diffs += d.length;
    results.failed.push(tag);
    console.log(`KHÁC  ${tag}`);
    d.forEach((x) => console.log(`        ${x}`));
  } else if (VERBOSE) console.log(`giống ${tag}`);
}

const run = Symbol('run');

class Scenario {
  constructor(name) {
    this.name = name;
    this.jars = { node: new Map(), java: new Map() };
    // Mã hợp lệ về dạng nhưng không tồn tại: Mongo ObjectId 24 ký tự hex, MySQL id số
    this.vars = { node: { missing: 'a'.repeat(24) }, java: { missing: '999999999' } };
  }
  jar(server, name) {
    if (!this.jars[server].has(name)) this.jars[server].set(name, new Jar());
    return this.jars[server].get(name);
  }
  /**
   * @param label   mô tả bước
   * @param as      tên kho cookie ('khach', 'letan', ...)
   * @param method, path (chuỗi hoặc (v)=>chuỗi), opts { body, raw, contentType, mode, ignore, save, expectStatus }
   */
  async step(label, as, method, path, opts = {}) {
    const resp = {};
    for (const server of ['node', 'java']) {
      const v = this.vars[server];
      const rr = (x) => (typeof x === 'function' ? x(v) : typeof x === 'string' ? x.replaceAll('{MISSING}', v.missing) : x);
      resp[server] = await call(server, this.jar(server, as), {
        method,
        path: rr(path),
        body: rr(opts.body),
        raw: rr(opts.raw),
        contentType: opts.contentType,
        headers: opts.headers,
      });
      if (opts.save) Object.assign(v, opts.save(resp[server].json ?? {}, resp[server], v));
    }
    const step = { ...opts, shape: opts.mode === 'shape' };
    if (SHOW && label.includes(SHOW)) {
      for (const k of ['node', 'java']) console.log(`--- ${label} [${k}] ${resp[k].status}`, JSON.stringify(resp[k].json ?? resp[k].buf.length).slice(0, 700));
    }
    const d = compare(step, resp.node, resp.java);
    results.steps++;
    const tag = `[${this.name}] ${label} (${method} ${typeof path === 'function' ? path(this.vars.node) : path})`;
    if (d.length) {
      results.diffs += d.length;
      results.failed.push(tag);
      console.log(`KHÁC  ${tag}  -> node ${resp.node.status} / java ${resp.java.status}`);
      d.forEach((x) => console.log(`        ${x}`));
    } else if (VERBOSE) {
      console.log(`giống ${tag}  -> ${resp.node.status}${resp.node.json?.error ? ` ${resp.node.json.error.code}: ${resp.node.json.error.message}` : ''}`);
    }
    return resp;
  }
}

const addDays = (yyyyMmDd, n) => {
  const d = new Date(`${yyyyMmDd}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

// Số điện thoại riêng cho mỗi lần chạy để chạy lại được trên server đã chạy sẵn
const stamp = () => String(randomInt(0, 10_000_000)).padStart(7, '0');
const RUN = stamp();
const phone = (n) => `09${n}${RUN}`.slice(0, 10);
const P_USER = phone(1);
const P_USER2 = phone(2);
const P_FAMILY = phone(3);
const P_STAFF_PT = phone(4);

const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);

const ok = (x) => x;
const meta = new Scenario('meta');
const today = (await call('node', new Jar(), { method: 'GET', path: '/api/meta' })).json?.today;
if (!today) { console.error('Không gọi được /api/meta trên bản Node. Server đã chạy chưa?'); process.exit(2); }
const D = (n) => addDays(today, n);

// ---- 1. Công khai
{
  const s = meta;
  await s.step('meta', 'a', 'GET', '/api/meta');
  await s.step('danh sách bài viết', 'a', 'GET', '/api/posts', { mode: 'shape' });
  await s.step('bài viết nổi bật', 'a', 'GET', '/api/posts?featured=1&limit=3', { mode: 'shape' });
  await s.step('bài viết lọc chuyên mục sai', 'a', 'GET', '/api/posts?category=KHONGCO');
  await s.step('tìm bài viết không có', 'a', 'GET', '/api/posts?q=khongcobaivietnaoten12345');
  await s.step('bài viết không tồn tại', 'a', 'GET', '/api/posts/khong-co-bai-nay');
  await s.step('ảnh không tồn tại', 'a', 'GET', '/api/uploads/aaaaaaaaaaaaaaaaaaaaaaaa');
  await s.step('ảnh mã sai dạng', 'a', 'GET', '/api/uploads/xyz');
  await s.step('đường dẫn không có', 'a', 'GET', '/api/khong-co');
  await s.step('/api/auth/me khi chưa đăng nhập', 'a', 'GET', '/api/auth/me');
  await s.step('/api/me chưa đăng nhập', 'a', 'GET', '/api/me');
  await s.step('/api/staff/queue chưa đăng nhập', 'a', 'GET', '/api/staff/queue');
  await s.step('/api/admin/dashboard chưa đăng nhập', 'a', 'GET', '/api/admin/dashboard');
  await s.step('JSON hỏng', 'a', 'POST', '/api/auth/login', { body: '{"phone":', contentType: 'application/json' });
  await s.step('body không phải JSON', 'a', 'POST', '/api/auth/login', { body: 'phone=0912345678', contentType: 'application/x-www-form-urlencoded' });
  await s.step('body quá lớn', 'a', 'POST', '/api/auth/login', { body: JSON.stringify({ phone: '0912345678', pad: 'x'.repeat(1_200_000) }) });
  await s.step('đăng xuất khi chưa đăng nhập', 'a', 'POST', '/api/auth/logout', { body: {} });
}

// ---- 1b. Header
{
  const o = 'http://127.0.0.1:5180';
  await headerCheck('origin được phép', 'GET', '/api/meta', { Origin: o });
  await headerCheck('origin lạ', 'GET', '/api/meta', { Origin: 'http://evil.example' });
  await headerCheck('không có origin', 'GET', '/api/meta', {});
  await headerCheck('preflight', 'OPTIONS', '/api/auth/login', { Origin: o, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type,x-session-user' });
  await headerCheck('preflight origin lạ', 'OPTIONS', '/api/auth/login', { Origin: 'http://evil.example', 'Access-Control-Request-Method': 'POST' });
  await headerCheck('lỗi 404', 'GET', '/api/khong-co', { Origin: o });
}

// ---- 2. Người dùng: đăng ký, đăng nhập, hồ sơ, đặt lịch
{
  const s = new Scenario('người dùng');
  const reg = { fullName: 'Nguyễn Văn Thử', phone: P_USER, dob: '1960-01-01', gender: 'NAM', address: 'Bình Thạnh' };
  await s.step('đăng ký thiếu trường', 'u', 'POST', '/api/auth/register', { body: {} });
  await s.step('đăng ký sai dữ liệu', 'u', 'POST', '/api/auth/register', { body: { fullName: 'A', phone: '123', dob: 'abc', gender: 'X' } });
  await s.step('đăng ký ngày sinh tương lai', 'u', 'POST', '/api/auth/register', { body: { ...reg, dob: D(5) } });
  await s.step('đăng ký', 'u', 'POST', '/api/auth/register', { body: reg, save: (b) => ({ code: b.devCode }) });
  await s.step('gửi lại mã đăng ký quá sớm', 'u', 'POST', '/api/auth/otp/resend', { body: { phone: P_USER, purpose: 'REGISTER' } });
  await s.step('xác nhận thiếu mã', 'u', 'POST', '/api/auth/register/verify', { body: { phone: P_USER } });
  await s.step('xác nhận mã sai', 'u', 'POST', '/api/auth/register/verify', { body: { phone: P_USER, code: '000000' } });
  await s.step('xác nhận sai định dạng mã', 'u', 'POST', '/api/auth/register/verify', { body: { phone: P_USER, code: '12' } });
  await s.step('xác nhận đúng mã', 'u', 'POST', '/api/auth/register/verify', { body: (v) => ({ phone: P_USER, code: v.code }), expectStatus: 201 });
  await s.step('dùng lại mã', 'u', 'POST', '/api/auth/register/verify', { body: (v) => ({ phone: P_USER, code: v.code }) });
  await s.step('đăng ký lại số đã có', 'u2', 'POST', '/api/auth/register', { body: reg });
  await s.step('auth/me', 'u', 'GET', '/api/auth/me');
  await s.step('me', 'u', 'GET', '/api/me');
  await s.step('sửa số điện thoại', 'u', 'PATCH', '/api/me', { body: { phone: '0900000099' } });
  await s.step('sửa thông tin sai', 'u', 'PATCH', '/api/me', { body: { fullName: '', dob: '', address: '' } });
  await s.step('sửa thông tin', 'u', 'PATCH', '/api/me', { body: { fullName: 'Nguyễn Văn Thử Hai', address: 'Gò Vấp' } });
  await s.step('sửa thông tin phiên bản cũ', 'u', 'PATCH', '/api/me', { body: { fullName: 'Cũ', expectedUpdatedAt: '2020-01-01T00:00:00.000Z' } });
  await s.step('danh sách hồ sơ', 'u', 'GET', '/api/me/patients', {
    save: (b) => ({ selfId: b.items?.[0]?.id }),
  });
  await s.step('tạo hồ sơ thiếu trường', 'u', 'POST', '/api/me/patients', { body: {} });
  await s.step('tạo hồ sơ trùng chính chủ', 'u', 'POST', '/api/me/patients', {
    body: { fullName: 'Người Khác', dob: '1970-01-01', gender: 'NU', relationship: 'BAN_THAN' },
  });
  await s.step('tạo hồ sơ người thân', 'u', 'POST', '/api/me/patients', {
    body: { fullName: 'Trần Thị Thân', phone: P_FAMILY, birthYear: 1955, gender: 'NU', relationship: 'CHA_ME', address: 'Quận 1', nationalId: '012345678901', email: 'THAN@Example.com' },
    expectStatus: 201,
    save: (b) => ({ famId: b.patient?.id }),
  });
  await s.step('tạo hồ sơ năm sinh sai', 'u', 'POST', '/api/me/patients', {
    body: { fullName: 'Lê Văn Sai', birthYear: 1800, gender: 'NAM', relationship: 'KHAC' },
  });
  await s.step('xem hồ sơ', 'u', 'GET', (v) => `/api/me/patients/${v.famId}`);
  await s.step('xem hồ sơ không tồn tại', 'u', 'GET', '/api/me/patients/{MISSING}');
  await s.step('xem hồ sơ id sai dạng', 'u', 'GET', '/api/me/patients/abc');
  await s.step('đặt lịch id hồ sơ sai dạng', 'u', 'POST', '/api/me/appointments', {
    body: { patientId: 'abc', serviceType: 'CHAM_CUU', slotDate: D(2), slotStart: '09:00' },
  });
  await s.step('sửa hồ sơ', 'u', 'PATCH', (v) => `/api/me/patients/${v.famId}`, { body: { address: 'Quận 3', email: '' } });
  await s.step('sửa hồ sơ sai quan hệ', 'u', 'PATCH', (v) => `/api/me/patients/${v.famId}`, { body: { relationship: 'ABC' } });
  await s.step('danh sách hồ sơ sau khi thêm', 'u', 'GET', '/api/me/patients');
  await s.step('khung giờ trống thiếu ngày', 'u', 'GET', '/api/me/availability');
  await s.step('khung giờ trống (ngày gần có phiếu demo)', 'u', 'GET', `/api/me/availability?date=${D(2)}`, { ignore: ['booked', 'remaining'] });
  await s.step('khung giờ trống (ngày xa không có phiếu demo)', 'u', 'GET', `/api/me/availability?date=${D(25)}`);
  await s.step('khung giờ trống ngày quá xa', 'u', 'GET', `/api/me/availability?date=${D(90)}`);
  await s.step('khung giờ trống ngày quá khứ', 'u', 'GET', `/api/me/availability?date=${D(-2)}`);
  await s.step('đặt lịch thiếu trường', 'u', 'POST', '/api/me/appointments', { body: {} });
  await s.step('đặt lịch ngày quá khứ', 'u', 'POST', '/api/me/appointments', {
    body: (v) => ({ patientId: v.selfId, serviceType: 'CHAM_CUU', slotDate: D(-1), slotStart: '09:00' }),
  });
  await s.step('đặt lịch hồ sơ không phải của mình', 'u', 'POST', '/api/me/appointments', {
    body: (v) => ({ patientId: v.missing, serviceType: 'CHAM_CUU', slotDate: D(2), slotStart: '09:00' }),
  });
  await s.step('đặt lịch', 'u', 'POST', '/api/me/appointments', {
    body: (v) => ({ patientId: v.selfId, serviceType: 'CHAM_CUU', slotDate: D(2), slotStart: '09:00' }),
    expectStatus: 201,
    save: (b) => ({ apptId: b.appointment?.id }),
  });
  await s.step('đặt trùng lịch trong ngày', 'u', 'POST', '/api/me/appointments', {
    body: (v) => ({ patientId: v.selfId, serviceType: 'CHAM_CUU', slotDate: D(2), slotStart: '10:00' }),
  });
  await s.step('đặt lịch cho người thân', 'u', 'POST', '/api/me/appointments', {
    body: (v) => ({ patientId: v.famId, serviceType: 'XOA_BOP_BAM_HUYET', slotDate: D(3), slotStart: '09:00' }),
    expectStatus: 201,
    save: (b) => ({ appt2Id: b.appointment?.id }),
  });
  await s.step('danh sách phiếu', 'u', 'GET', '/api/me/appointments');
  await s.step('danh sách phiếu lọc trạng thái', 'u', 'GET', '/api/me/appointments?status=UPCOMING');
  await s.step('danh sách phiếu lọc trạng thái sai', 'u', 'GET', '/api/me/appointments?status=ABC');
  await s.step('chi tiết phiếu', 'u', 'GET', (v) => `/api/me/appointments/${v.apptId}`);
  await s.step('chi tiết phiếu không có', 'u', 'GET', '/api/me/appointments/{MISSING}');
  await s.step('kết quả khám chưa có', 'u', 'GET', (v) => `/api/me/appointments/${v.apptId}/result`);
  await s.step('lịch sử khám', 'u', 'GET', '/api/me/history');
  await s.step('huỷ phiếu', 'u', 'POST', (v) => `/api/me/appointments/${v.apptId}/cancel`, { body: { reason: 'Bận việc' } });
  await s.step('huỷ phiếu đã huỷ', 'u', 'POST', (v) => `/api/me/appointments/${v.apptId}/cancel`, { body: {} });
  await s.step('đặt lại sau khi huỷ', 'u', 'POST', '/api/me/appointments', {
    body: (v) => ({ patientId: v.selfId, serviceType: 'CHAM_CUU', slotDate: D(2), slotStart: '09:00', rebookFromId: v.apptId }),
    expectStatus: 201,
  });
  await s.step('xoá hồ sơ có phiếu', 'u', 'DELETE', (v) => `/api/me/patients/${v.famId}`);
  await s.step('xoá hồ sơ chính chủ', 'u', 'DELETE', (v) => `/api/me/patients/${v.selfId}`);
  await s.step('đổi mật khẩu của người dùng', 'u', 'POST', '/api/auth/change-password', { body: { currentPassword: 'a', newPassword: 'ChucTu@2027' } });
  await s.step('người dùng vào khu tiếp tân', 'u', 'GET', '/api/staff/queue');
  await s.step('người dùng vào khu admin', 'u', 'GET', '/api/admin/dashboard');
  await s.step('đăng xuất', 'u', 'POST', '/api/auth/logout', { body: {} });
  await s.step('me sau đăng xuất', 'u', 'GET', '/api/me');
  // Đăng nhập lại: thiết bị đã nhớ (cookie thiết bị còn trong kho) thì không cần mã
  await s.step('đăng nhập thiết bị đã nhớ', 'u', 'POST', '/api/auth/login', { body: { phone: P_USER } });
  await s.step('đăng nhập số chưa có', 'u', 'POST', '/api/auth/login', { body: { phone: '0988888888' } });
  await s.step('đăng nhập sai số', 'u', 'POST', '/api/auth/login', { body: { phone: 'abc' } });
  // Thiết bị mới cần mã
  await s.step('đăng nhập thiết bị mới', 'u2', 'POST', '/api/auth/login', { body: { phone: P_USER }, save: (b) => ({ code2: b.devCode }) });
  await s.step('xác nhận thiết bị mới sai mã', 'u2', 'POST', '/api/auth/login/verify', { body: { phone: P_USER, code: '000000' } });
  await s.step('xác nhận thiết bị mới', 'u2', 'POST', '/api/auth/login/verify', { body: (v) => ({ phone: P_USER, code: v.code2, remember: false }) });
  await s.step('gửi lại mã sai mục đích', 'u2', 'POST', '/api/auth/otp/resend', { body: { phone: P_USER, purpose: 'XYZ' } });
  await s.step('me khi đăng nhập không nhớ', 'u2', 'GET', '/api/me');
  await s.step('đăng ký lần hai (người dùng thứ hai)', 'u3', 'POST', '/api/auth/register', {
    body: { ...reg, phone: P_USER2, fullName: 'Hai' },
    save: (b) => ({ code: b.devCode }),
  });
  await s.step('gửi lại mã đăng ký', 'u3', 'POST', '/api/auth/otp/resend', { body: { phone: P_USER2, purpose: 'REGISTER' } });
}

// ---- 3. Tiếp tân: đăng nhập, hồ sơ, đặt hộ, check-in, hàng đợi, phiếu kết quả
{
  const s = new Scenario('tiếp tân');
  const login = (identifier, password) => ({ identifier, password });
  await s.step('đăng nhập thiếu trường', 'l', 'POST', '/api/auth/staff/login', { body: {} });
  await s.step('đăng nhập sai mật khẩu', 'l', 'POST', '/api/auth/staff/login', { body: login('0900000002', 'sai') });
  await s.step('đăng nhập tài khoản không có', 'l', 'POST', '/api/auth/staff/login', { body: login('khong@co.vn', 'sai') });
  await s.step('đăng nhập', 'l', 'POST', '/api/auth/staff/login', { body: login('0900000002', 'ChucTu@2026'), expectStatus: 200 });
  await s.step('đăng nhập bằng email mẫu hoặc email đã đổi', 'l2', 'POST', '/api/auth/staff/login', { body: login('admin@chuctu.vn', 'ChucTu@2026') });
  await s.step('auth/me', 'l', 'GET', '/api/auth/me');
  await s.step('tab khác đã đổi tài khoản (x-session-user sai)', 'l', 'GET', '/api/staff/queue', { headers: { 'x-session-user': '0' } });
  await s.step('x-session-user sai nhưng gọi /api/auth/me', 'l', 'GET', '/api/auth/me', { headers: { 'x-session-user': '0' } });
  await s.step('tiếp tân vào khu admin', 'l', 'GET', '/api/admin/dashboard');
  await s.step('tiếp tân vào khu người dùng', 'l', 'GET', '/api/me');
  await s.step('sửa họ tên', 'l', 'PATCH', '/api/staff/account', { body: { fullName: 'Trần Thị Thu' } });
  await s.step('sửa họ tên rỗng', 'l', 'PATCH', '/api/staff/account', { body: { fullName: '' } });
  await s.step('đổi email sai', 'l', 'POST', '/api/staff/account/email', { body: { email: 'abc' } });
  await s.step('đổi email trùng', 'l', 'POST', '/api/staff/account/email', { body: { email: 'admin@chuctu.vn' } });
  await s.step('đổi email', 'l', 'POST', '/api/staff/account/email', { body: { email: `LeTan${RUN}@Example.com` } });
  await s.step('xác nhận email sai mã', 'l', 'POST', '/api/staff/account/email/verify', { body: { code: '000000' } });
  await s.step('xác nhận email sai định dạng', 'l', 'POST', '/api/staff/account/email/verify', { body: { code: 'ab' } });
  await s.step('xác nhận email', 'l', 'POST', '/api/staff/account/email/verify', { body: { code: '123456' } });
  await s.step('đổi mật khẩu sai mật khẩu hiện tại', 'l', 'POST', '/api/auth/change-password', { body: { currentPassword: 'sai', newPassword: 'ChucTu@2027' } });
  await s.step('đổi mật khẩu trùng', 'l', 'POST', '/api/auth/change-password', { body: { currentPassword: 'ChucTu@2026', newPassword: 'ChucTu@2026' } });
  await s.step('đổi mật khẩu ngắn', 'l', 'POST', '/api/auth/change-password', { body: { currentPassword: 'ChucTu@2026', newPassword: '123' } });

  await s.step('lịch trong ngày', 'l', 'GET', '/api/staff/appointments', { mode: 'shape' });
  await s.step('lịch ngày sai định dạng', 'l', 'GET', '/api/staff/appointments?date=abc');
  await s.step('lịch ngày xa không có', 'l', 'GET', `/api/staff/appointments?date=${D(60)}`);
  await s.step('lịch lọc trạng thái', 'l', 'GET', '/api/staff/appointments?status=BOOKED', { mode: 'shape' });
  await s.step('ngày có phiếu trong tháng', 'l', 'GET', `/api/staff/appointments/dates?month=${today.slice(0, 7)}`, { mode: 'shape' });
  await s.step('ngày có phiếu sai tháng', 'l', 'GET', '/api/staff/appointments/dates?month=2026');
  await s.step('tra mã phiếu ngắn', 'l', 'GET', '/api/staff/appointments/lookup?code=ab');
  await s.step('tra mã phiếu không có', 'l', 'GET', '/api/staff/appointments/lookup?code=XX-0000-000');
  await s.step('chi tiết phiếu không có', 'l', 'GET', '/api/staff/appointments/{MISSING}');
  await s.step('khung giờ trống quầy', 'l', 'GET', `/api/staff/availability?date=${D(1)}`, { ignore: ['booked', 'remaining'] });
  await s.step('khung giờ trống quầy ngày xa', 'l', 'GET', `/api/staff/availability?date=${D(6)}`, { ignore: ['booked', 'remaining'] });
  await s.step('khung giờ trống quầy hôm nay', 'l', 'GET', `/api/staff/availability?date=${today}`, { mode: 'shape' });

  await s.step('danh sách hồ sơ', 'l', 'GET', '/api/staff/patients?limit=5', { mode: 'shape' });
  await s.step('danh sách hồ sơ tìm theo tên', 'l', 'GET', `/api/staff/patients?q=${encodeURIComponent('Nguyễn')}&limit=5`, { mode: 'shape' });
  await s.step('danh sách hồ sơ bị khoá', 'l', 'GET', '/api/staff/patients?locked=1', { mode: 'shape' });
  await s.step('danh sách hồ sơ phân trang xa', 'l', 'GET', '/api/staff/patients?page=9999');
  await s.step('hồ sơ trùng số', 'l', 'GET', '/api/staff/patients/duplicates?phone=0912345678', { mode: 'shape' });
  await s.step('tạo hồ sơ thiếu trường', 'l', 'POST', '/api/staff/patients', { body: {} });
  await s.step('tạo hồ sơ', 'l', 'POST', '/api/staff/patients', {
    body: { fullName: 'Phạm Văn Quầy', phone: P_STAFF_PT, birthYear: 1950, gender: 'NAM', address: 'Quận 5', nationalId: '012345678', email: 'Quay@Example.com' },
    expectStatus: 201,
    save: (b) => ({ ptId: b.patient?.id }),
  });
  await s.step('tạo hồ sơ trùng số điện thoại', 'l', 'POST', '/api/staff/patients', {
    body: { fullName: 'Phạm Thị Trùng', phone: P_STAFF_PT, birthYear: 1952 },
  });
  await s.step('tạo hồ sơ xác nhận trùng', 'l', 'POST', '/api/staff/patients', {
    body: { fullName: 'Phạm Thị Trùng', phone: P_STAFF_PT, birthYear: 1952, confirmDuplicate: true },
    expectStatus: 201,
    save: (b) => ({ pt2Id: b.patient?.id }),
  });
  await s.step('hồ sơ trùng số có dữ liệu', 'l', 'GET', `/api/staff/patients/duplicates?phone=${P_STAFF_PT}`);
  await s.step('hồ sơ trùng số loại trừ', 'l', 'GET', (v) => `/api/staff/patients/duplicates?phone=${P_STAFF_PT}&excludeId=${v.ptId}`);
  await s.step('xem hồ sơ', 'l', 'GET', (v) => `/api/staff/patients/${v.ptId}`);
  await s.step('xem hồ sơ không có', 'l', 'GET', '/api/staff/patients/{MISSING}');
  await s.step('sửa hồ sơ', 'l', 'PATCH', (v) => `/api/staff/patients/${v.ptId}`, { body: { address: 'Quận 6', email: null, nationalId: '' } });
  await s.step('sửa hồ sơ sai', 'l', 'PATCH', (v) => `/api/staff/patients/${v.ptId}`, { body: { birthYear: 3000, phone: 'abc' } });
  await s.step('sửa hồ sơ phiên bản cũ', 'l', 'PATCH', (v) => `/api/staff/patients/${v.ptId}`, { body: { address: 'X', expectedUpdatedAt: '2020-01-01T00:00:00.000Z' } });
  await s.step('tìm hồ sơ vừa tạo', 'l', 'GET', `/api/staff/patients?q=${P_STAFF_PT}`);
  await s.step('tìm hồ sơ có ký tự % và _', 'l', 'GET', '/api/staff/patients?q=%25_%5C');

  await s.step('đặt hộ thiếu trường', 'l', 'POST', '/api/staff/appointments', { body: {} });
  await s.step('đặt hộ ngày quá khứ', 'l', 'POST', '/api/staff/appointments', {
    body: (v) => ({ patientId: v.ptId, serviceType: 'CHAM_CUU', slotDate: D(-1), slotStart: '09:00' }),
  });
  await s.step('đặt hộ ngày mai', 'l', 'POST', '/api/staff/appointments', {
    body: (v) => ({ patientId: v.ptId, serviceType: 'CHAM_CUU', slotDate: D(1), slotStart: '10:00' }),
    expectStatus: 201,
    save: (b) => ({ aTom: b.appointment?.id, ticket: b.appointment?.ticketCode }),
  });
  await s.step('đặt hộ trùng', 'l', 'POST', '/api/staff/appointments', {
    body: (v) => ({ patientId: v.ptId, serviceType: 'CHAM_CUU', slotDate: D(1), slotStart: '11:00' }),
  });
  await s.step('tra mã phiếu', 'l', 'GET', (v) => `/api/staff/appointments/lookup?code=${encodeURIComponent(v.ticket)}`);
  await s.step('đổi giờ (tạo phiếu mới, phiếu cũ bị huỷ)', 'l', 'POST', (v) => `/api/staff/appointments/${v.aTom}/reschedule`, {
    body: { slotDate: D(1), slotStart: '13:00' },
    save: (b) => ({ aTomNew: b.appointment?.id }),
  });
  await s.step('chi tiết phiếu cũ sau khi đổi giờ', 'l', 'GET', (v) => `/api/staff/appointments/${v.aTom}`);
  await s.step('đổi giờ phiếu đã đổi', 'l', 'POST', (v) => `/api/staff/appointments/${v.aTom}/reschedule`, { body: { slotDate: D(1), slotStart: '14:00' } });
  await s.step('đổi giờ sai giờ', 'l', 'POST', (v) => `/api/staff/appointments/${v.aTomNew}/reschedule`, { body: { slotDate: D(1), slotStart: '03:00' } });
  await s.step('đổi giờ thiếu', 'l', 'POST', (v) => `/api/staff/appointments/${v.aTomNew}/reschedule`, { body: {} });
  await s.step('chi tiết phiếu', 'l', 'GET', (v) => `/api/staff/appointments/${v.aTomNew}`);
  await s.step('huỷ thiếu lý do', 'l', 'POST', (v) => `/api/staff/appointments/${v.aTomNew}/cancel`, { body: {} });
  await s.step('huỷ', 'l', 'POST', (v) => `/api/staff/appointments/${v.aTomNew}/cancel`, { body: { reason: 'Đặt nhầm' } });
  await s.step('huỷ lại phiếu đã huỷ', 'l', 'POST', (v) => `/api/staff/appointments/${v.aTomNew}/cancel`, { body: { reason: 'Đặt nhầm' } });
  await s.step('check-in phiếu đã huỷ', 'l', 'POST', (v) => `/api/staff/appointments/${v.aTomNew}/checkin`, { body: {} });

  // Hôm nay: đặt và check-in ngay
  await s.step('đặt hộ hôm nay và check-in ngay', 'l', 'POST', '/api/staff/appointments', {
    body: (v) => ({ patientId: v.ptId, serviceType: 'CHAM_CUU', slotDate: today, slotStart: '16:00', checkInNow: true }),
    save: (b) => ({ aNow: b.appointment?.id }),
    ignore: [],
  });
  await s.step('check-in phiếu hôm nay', 'l', 'POST', (v) => `/api/staff/appointments/${v.aNow}/checkin`, { body: {} });
  await s.step('hàng đợi', 'l', 'GET', '/api/staff/queue', { mode: 'shape' });
  await s.step('hoàn tác check-in', 'l', 'POST', (v) => `/api/staff/appointments/${v.aNow}/undo-checkin`, { body: {} });
  await s.step('hoàn tác check-in lần hai', 'l', 'POST', (v) => `/api/staff/appointments/${v.aNow}/undo-checkin`, { body: {} });
  await s.step('mời vào khám khi chưa check-in', 'l', 'POST', (v) => `/api/staff/appointments/${v.aNow}/enter`, { body: {} });
  await s.step('check-in lại', 'l', 'POST', (v) => `/api/staff/appointments/${v.aNow}/checkin`, { body: {} });
  await s.step('mời vào khám', 'l', 'POST', (v) => `/api/staff/appointments/${v.aNow}/enter`, { body: {} });
  await s.step('chi tiết phiếu đang khám', 'l', 'GET', (v) => `/api/staff/appointments/${v.aNow}`);
  await s.step('tạo kết quả sai', 'l', 'POST', (v) => `/api/staff/appointments/${v.aNow}/record`, { body: { visitDate: 'abc', treatmentNotes: { acupuncture: 5 } } });
  await s.step('tạo kết quả', 'l', 'POST', (v) => `/api/staff/appointments/${v.aNow}/record`, {
    body: {
      diagnosis: 'Đau vai gáy', pulse: '78', bloodPressure: '130/80', medicalHistory: 'Cao huyết áp',
      treatmentNotes: { acupuncture: 'Châm cứu 20 phút', other: 'Nghỉ ngơi' }, followUpDate: D(7), doctorName: 'BS. An',
    },
    expectStatus: 201,
    save: (b) => ({ recId: b.record?.id }),
  });
  await s.step('tạo kết quả lần hai', 'l', 'POST', (v) => `/api/staff/appointments/${v.aNow}/record`, { body: { diagnosis: 'Lần hai' } });
  await s.step('xem kết quả', 'l', 'GET', (v) => `/api/staff/records/${v.recId}`);
  await s.step('xem kết quả không có', 'l', 'GET', '/api/staff/records/{MISSING}');
  await s.step('sửa kết quả', 'l', 'PUT', (v) => `/api/staff/records/${v.recId}`, { body: { diagnosis: 'Đau vai gáy (sửa)', followUpDate: null, treatmentNotes: { acupressure: 'Bấm huyệt' } } });
  await s.step('sửa kết quả sai', 'l', 'PUT', (v) => `/api/staff/records/${v.recId}`, { body: { followUpDate: 'abc' } });
  await s.step('chi tiết phiếu đã hoàn thành', 'l', 'GET', (v) => `/api/staff/appointments/${v.aNow}`);
  await s.step('hồ sơ sau khám', 'l', 'GET', (v) => `/api/staff/patients/${v.ptId}`);
  await s.step('hàng đợi sau khám', 'l', 'GET', '/api/staff/queue', { mode: 'shape' });
  await s.step('mời người kế tiếp', 'l', 'POST', '/api/staff/queue/invite', { body: {}, mode: 'shape' });
  await s.step('hoàn tác mời id sai dạng', 'l', 'POST', '/api/staff/queue/abc/undo-enter', { body: {} });
  await s.step('hoàn tác mời không có', 'l', 'POST', '/api/staff/queue/{MISSING}/undo-enter', { body: {} });
  await s.step('tự động mời sai kiểu', 'l', 'PUT', '/api/staff/queue/auto-invite', { body: { enabled: 'yes' } });
  await s.step('tự động mời bật', 'l', 'PUT', '/api/staff/queue/auto-invite', { body: { enabled: true }, mode: 'shape' });
  await s.step('tự động mời tắt', 'l', 'PUT', '/api/staff/queue/auto-invite', { body: { enabled: false }, mode: 'shape' });
  await s.step('quên mật khẩu email sai', 'f', 'POST', '/api/auth/staff/forgot-password', { body: { email: 'abc' } });
  await s.step('quên mật khẩu email lạ', 'f', 'POST', '/api/auth/staff/forgot-password', { body: { email: 'la@example.com' } });
  await s.step('đăng xuất tiếp tân', 'l', 'POST', '/api/auth/logout', { body: {} });
  await s.step('queue sau đăng xuất', 'l', 'GET', '/api/staff/queue');
}

// ---- 4. Tiếp tân xem lịch người dùng đặt, lịch hôm nay của người dùng: check-in hồ sơ do người dùng tạo
{
  const s = new Scenario('người dùng đặt, tiếp tân khám');
  const reg = { fullName: 'Lê Thị Chéo', phone: phone(5), dob: '1958-05-05', gender: 'NU', address: 'Phú Nhuận' };
  await s.step('đăng ký', 'u', 'POST', '/api/auth/register', { body: reg, save: (b) => ({ code: b.devCode }) });
  await s.step('xác nhận', 'u', 'POST', '/api/auth/register/verify', { body: (v) => ({ phone: reg.phone, code: v.code }) });
  await s.step('hồ sơ', 'u', 'GET', '/api/me/patients', { save: (b) => ({ selfId: b.items?.[0]?.id }) });
  await s.step('đặt hôm nay', 'u', 'POST', '/api/me/appointments', {
    body: (v) => ({ patientId: v.selfId, serviceType: 'CHAM_CUU', slotDate: today, slotStart: '16:00' }),
    expectStatus: 201,
    save: (b) => ({ apptId: b.appointment?.id, ticket: b.appointment?.ticketCode }),
  });
  await s.step('khung giờ hôm nay', 'u', 'GET', `/api/me/availability?date=${today}`, { ignore: ['booked', 'remaining'] });
  await s.step('đăng nhập tiếp tân', 'l', 'POST', '/api/auth/staff/login', { body: { identifier: '0900000002', password: 'ChucTu@2026' } });
  await s.step('check-in', 'l', 'POST', (v) => `/api/staff/appointments/${v.apptId}/checkin`, { body: {}, expectStatus: 200 });
  await s.step('người dùng xem phiếu sau check-in', 'u', 'GET', (v) => `/api/me/appointments/${v.apptId}`);
  await s.step('hàng đợi sau check-in', 'l', 'GET', '/api/staff/queue', {
    mode: 'shape',
    save: (b) => ({ occId: (b.seats ?? []).find((x) => !x.empty)?.id }),
  });
  // Phòng khám có thể đang đủ 5 người (4 người của dữ liệu demo cộng người vừa được mời): trả một người về hàng chờ để có ghế
  await s.step('hoàn tác mời một người đang trong phòng', 'l', 'POST', (v) => `/api/staff/queue/${v.occId}/undo-enter`, { body: {}, mode: 'shape', expectStatus: 200 });
  await s.step('mời vào khám', 'l', 'POST', (v) => `/api/staff/appointments/${v.apptId}/enter`, { body: {}, expectStatus: 200 });
  await s.step('hàng đợi sau khi mời', 'l', 'GET', '/api/staff/queue', {
    mode: 'shape',
    save: (b, _r, v) => ({ ciId: (b.seats ?? []).find((x) => x.appointmentId === v.apptId)?.id }),
  });
  await s.step('hoàn tác mời theo phiếu', 'l', 'POST', (v) => `/api/staff/appointments/${v.apptId}/undo-enter`, { body: {}, expectStatus: 200 });
  await s.step('mời lại', 'l', 'POST', (v) => `/api/staff/appointments/${v.apptId}/enter`, { body: {}, expectStatus: 200 });
  await s.step('hoàn tác mời theo mã check-in', 'l', 'POST', (v) => `/api/staff/queue/${v.ciId}/undo-enter`, { body: {}, mode: 'shape' });
  await s.step('mời lại sau khi hoàn tác theo mã check-in', 'l', 'POST', (v) => `/api/staff/appointments/${v.apptId}/enter`, { body: {}, expectStatus: 200 });
  await s.step('kết quả khi chưa lưu', 'u', 'GET', (v) => `/api/me/appointments/${v.apptId}/result`);
  await s.step('lưu kết quả', 'l', 'POST', (v) => `/api/staff/appointments/${v.apptId}/record`, {
    body: { diagnosis: 'Mệt mỏi', treatmentNotes: { other: 'Bốc thuốc' }, followUpDate: D(14) },
    expectStatus: 201,
    save: (b) => ({ recId: b.record?.id }),
  });
  await s.step('người dùng xem kết quả', 'u', 'GET', (v) => `/api/me/appointments/${v.apptId}/result`, { expectStatus: 200 });
  await s.step('người dùng xem lịch sử', 'u', 'GET', '/api/me/history');
  await s.step('lịch sử theo hồ sơ', 'u', 'GET', (v) => `/api/me/history?patientId=${v.selfId}`);
  await s.step('lịch sử theo hồ sơ không có', 'u', 'GET', '/api/me/history?patientId={MISSING}');
  await s.step('người dùng huỷ phiếu đã hoàn thành', 'u', 'POST', (v) => `/api/me/appointments/${v.apptId}/cancel`, { body: {} });
  await s.step('lịch hôm nay', 'u', 'GET', '/api/me/appointments?status=DONE');
  await s.step('tìm theo tên trên lịch hôm nay', 'l', 'GET', `/api/staff/appointments?q=${encodeURIComponent('Chéo')}`);
}

// ---- 5. Admin
{
  const s = new Scenario('admin');
  const A = 'a';
  await s.step('đăng nhập', A, 'POST', '/api/auth/staff/login', { body: { identifier: 'admin@chuctu.vn', password: 'ChucTu@2026' } });
  await s.step('bảng điều khiển', A, 'GET', '/api/admin/dashboard', { mode: 'shape' });
  await s.step('admin dùng API tiếp tân', A, 'GET', '/api/staff/queue', { mode: 'shape' });
  await s.step('danh sách tài khoản', A, 'GET', '/api/admin/accounts?limit=5', { mode: 'shape' });
  await s.step('lọc tài khoản theo vai trò', A, 'GET', '/api/admin/accounts?role=ADMIN');
  await s.step('lọc tài khoản vai trò sai', A, 'GET', '/api/admin/accounts?role=ABC', { mode: 'shape' });
  await s.step('tìm tài khoản', A, 'GET', '/api/admin/accounts?q=letan');
  await s.step('tìm tài khoản ký tự đặc biệt', A, 'GET', '/api/admin/accounts?q=%25_%5C');
  await s.step('lọc tài khoản trạng thái', A, 'GET', '/api/admin/accounts?status=LOCKED', { mode: 'shape' });
  await s.step('tạo tài khoản thiếu trường', A, 'POST', '/api/admin/accounts', { body: {} });
  await s.step('tạo tài khoản', A, 'POST', '/api/admin/accounts', {
    body: { fullName: 'Nhân Viên Mới', phone: phone(6), email: `NV${RUN}@Example.com`, role: 'RECEPTIONIST', tempPassword: 'MatKhau@123' },
    expectStatus: 201,
    save: (b) => ({ accId: b.account?.id }),
  });
  await s.step('tạo tài khoản trùng số', A, 'POST', '/api/admin/accounts', { body: { fullName: 'Trùng', phone: phone(6), role: 'RECEPTIONIST', tempPassword: 'MatKhau@123' } });
  await s.step('tạo tài khoản trùng email', A, 'POST', '/api/admin/accounts', { body: { fullName: 'Trùng', phone: phone(7), email: `nv${RUN}@example.com`, role: 'RECEPTIONIST', tempPassword: 'MatKhau@123' } });
  await s.step('tạo tài khoản mật khẩu ngắn', A, 'POST', '/api/admin/accounts', { body: { fullName: 'Ngắn', phone: phone(7), role: 'RECEPTIONIST', tempPassword: '123' } });
  await s.step('xem tài khoản', A, 'GET', (v) => `/api/admin/accounts/${v.accId}`);
  await s.step('xem tài khoản không có', A, 'GET', '/api/admin/accounts/{MISSING}');
  await s.step('sửa tài khoản', A, 'PATCH', (v) => `/api/admin/accounts/${v.accId}`, { body: { fullName: 'Nhân Viên Mới 2', address: 'Quận 7' } });
  await s.step('sửa tài khoản sai', A, 'PATCH', (v) => `/api/admin/accounts/${v.accId}`, { body: { phone: 'abc', email: 'xx' } });
  await s.step('sửa tài khoản phiên bản cũ', A, 'PATCH', (v) => `/api/admin/accounts/${v.accId}`, { body: { fullName: 'Cũ', expectedUpdatedAt: '2020-01-01T00:00:00.000Z' } });
  await s.step('khoá tài khoản', A, 'POST', (v) => `/api/admin/accounts/${v.accId}/lock`, { body: {} });
  await s.step('nhân viên bị khoá đăng nhập', 'nv', 'POST', '/api/auth/staff/login', { body: { identifier: phone(6), password: 'MatKhau@123' } });
  await s.step('mở khoá', A, 'POST', (v) => `/api/admin/accounts/${v.accId}/unlock`, { body: {} });
  await s.step('nhân viên đăng nhập', 'nv', 'POST', '/api/auth/staff/login', { body: { identifier: phone(6), password: 'MatKhau@123' } });
  await s.step('đặt lại mật khẩu ngắn', A, 'POST', (v) => `/api/admin/accounts/${v.accId}/reset-password`, { body: { tempPassword: '1' } });
  await s.step('đặt lại mật khẩu', A, 'POST', (v) => `/api/admin/accounts/${v.accId}/reset-password`, { body: { tempPassword: 'MatKhau@456' } });
  await s.step('phiên nhân viên sau khi đặt lại mật khẩu', 'nv', 'GET', '/api/auth/me');
  await s.step('đổi vai trò sai', A, 'PUT', (v) => `/api/admin/accounts/${v.accId}/role`, { body: { role: 'XYZ' } });
  await s.step('đổi vai trò', A, 'PUT', (v) => `/api/admin/accounts/${v.accId}/role`, { body: { role: 'ADMIN' } });
  await s.step('đổi vai trò về người dùng thiếu mật khẩu', A, 'PUT', (v) => `/api/admin/accounts/${v.accId}/role`, { body: { role: 'USER' } });
  await s.step('tự khoá mình', A, 'POST', '/api/admin/accounts/me/lock', { body: {} });
  await s.step('xem tài khoản của mình', A, 'GET', '/api/auth/me', { save: (b) => ({ myId: b.user?.id }) });
  await s.step('tự khoá mình theo id', A, 'POST', (v) => `/api/admin/accounts/${v.myId}/lock`, { body: {} });
  await s.step('tự đổi vai trò mình', A, 'PUT', (v) => `/api/admin/accounts/${v.myId}/role`, { body: { role: 'RECEPTIONIST' } });

  // Khoá hồ sơ bệnh nhân
  await s.step('tạo hồ sơ để khoá', A, 'POST', '/api/staff/patients', {
    body: { fullName: 'Hồ Sơ Khoá', phone: phone(8), birthYear: 1949 },
    save: (b) => ({ ptId: b.patient?.id }),
  });
  await s.step('khoá hồ sơ', A, 'POST', (v) => `/api/admin/patients/${v.ptId}/lock`, { body: {} });
  await s.step('đặt hộ hồ sơ bị khoá', A, 'POST', '/api/staff/appointments', {
    body: (v) => ({ patientId: v.ptId, serviceType: 'CHAM_CUU', slotDate: D(1), slotStart: '09:00' }),
  });
  await s.step('sửa hồ sơ bị khoá', A, 'PATCH', (v) => `/api/staff/patients/${v.ptId}`, { body: { address: 'X' } });
  await s.step('mở khoá hồ sơ', A, 'POST', (v) => `/api/admin/patients/${v.ptId}/unlock`, { body: {} });
  await s.step('khoá hồ sơ không có', A, 'POST', '/api/admin/patients/{MISSING}/lock', { body: {} });

  // Bài viết và ảnh
  await s.step('danh sách bài viết', A, 'GET', '/api/admin/posts?limit=5', { mode: 'shape' });
  await s.step('lọc bài viết', A, 'GET', '/api/admin/posts?status=DRAFT&category=TIN_TUC');
  await s.step('tạo bài viết thiếu trường', A, 'POST', '/api/admin/posts', { body: {} });
  await s.step('tạo bài viết sai trạng thái', A, 'POST', '/api/admin/posts', { body: { title: 'T', category: 'TIN_TUC', status: 'X' } });
  await s.step('tạo bài viết thumbnail sai', A, 'POST', '/api/admin/posts', { body: { title: 'T', category: 'TIN_TUC', thumbnailUrl: 'abc' } });
  await s.step('tải ảnh sai loại', A, 'POST', '/api/admin/uploads', { raw: Buffer.from('abc'), contentType: 'text/plain' });
  await s.step('tải ảnh rỗng', A, 'POST', '/api/admin/uploads', { raw: Buffer.alloc(0), contentType: 'image/png' });
  await s.step('tải ảnh hỏng', A, 'POST', '/api/admin/uploads', { raw: Buffer.from('không phải ảnh'), contentType: 'image/png' });
  await s.step('tải ảnh quá lớn', A, 'POST', '/api/admin/uploads', { raw: Buffer.alloc(3 * 1024 * 1024 + 10, 1), contentType: 'image/png' });
  await s.step('tải ảnh', A, 'POST', '/api/admin/uploads', { raw: PNG, contentType: 'image/png', expectStatus: 201, save: (b) => ({ imgUrl: b.url, imgId: b.id }) });
  await s.step('lấy ảnh', 'pub', 'GET', (v) => v.imgUrl ?? '/api/uploads/none');
  await s.step('tạo bài viết', A, 'POST', '/api/admin/posts', {
    body: (v) => ({
      title: 'Bài viết thử đối chiếu', category: 'TIN_TUC', summary: 'Mô tả', thumbnailUrl: v.imgUrl, authorName: 'BS. An',
      content: '<p>Nội dung</p>', status: 'DRAFT',
    }),
    expectStatus: 201,
    save: (b) => ({ postId: b.post?.id, slug: b.post?.slug }),
  });
  await s.step('bài viết nháp chưa công khai', 'pub', 'GET', (v) => `/api/posts/${v.postId}`);
  await s.step('xem bài viết admin', A, 'GET', (v) => `/api/admin/posts/${v.postId}`, { ignore: ['slug'] });
  await s.step('xem bài viết admin không có', A, 'GET', '/api/admin/posts/{MISSING}');
  await s.step('sửa bài viết sai', A, 'PUT', (v) => `/api/admin/posts/${v.postId}`, { body: { status: 'X' } });
  await s.step('đăng bài viết', A, 'PUT', (v) => `/api/admin/posts/${v.postId}`, { body: { status: 'PUBLISHED' } });
  await s.step('xem bài viết công khai bằng id', 'pub', 'GET', (v) => `/api/posts/${v.postId}`, { ignore: ['slug'] });
  await s.step('tìm bài viết công khai', 'pub', 'GET', `/api/posts?q=${encodeURIComponent('đối chiếu')}`, { ignore: ['slug'] });
  await s.step('ghim bài viết', A, 'POST', (v) => `/api/admin/posts/${v.postId}/pin`, { body: {} });
  await s.step('bài viết nổi bật', 'pub', 'GET', '/api/posts?featured=1', { mode: 'shape' });
  await s.step('bỏ ghim', A, 'POST', (v) => `/api/admin/posts/${v.postId}/unpin`, { body: {} });
  await s.step('ẩn bài viết', A, 'PUT', (v) => `/api/admin/posts/${v.postId}`, { body: { status: 'HIDDEN' } });
  await s.step('bài viết đã ẩn', 'pub', 'GET', (v) => `/api/posts/${v.postId}`);
  await s.step('xoá bài viết', A, 'DELETE', (v) => `/api/admin/posts/${v.postId}`);
  await s.step('xoá bài viết lần hai', A, 'DELETE', (v) => `/api/admin/posts/${v.postId}`);
  await s.step('ghim bài không có', A, 'POST', '/api/admin/posts/{MISSING}/pin', { body: {} });
}

// ---- 6. Quên mật khẩu bằng email (EMAIL_PROVIDER=console, mã mẫu 123456)
{
  const s = new Scenario('quên mật khẩu');
  const email = `QM${RUN}@Example.com`;
  await s.step('admin tạo nhân viên', 'a', 'POST', '/api/auth/staff/login', { body: { identifier: 'admin@chuctu.vn', password: 'ChucTu@2026' } });
  await s.step('tạo tài khoản', 'a', 'POST', '/api/admin/accounts', {
    body: { fullName: 'Quên Mật Khẩu', phone: phone(9), email, role: 'RECEPTIONIST', tempPassword: 'MatKhau@123' },
  });
  await s.step('quên mật khẩu', 'f', 'POST', '/api/auth/staff/forgot-password', { body: { email } });
  await s.step('quên mật khẩu gửi lại quá sớm', 'f', 'POST', '/api/auth/staff/forgot-password', { body: { email } });
  await s.step('xác nhận sai mã', 'f', 'POST', '/api/auth/staff/forgot-password/verify', { body: { email, code: '000000' } });
  await s.step('xác nhận sai định dạng', 'f', 'POST', '/api/auth/staff/forgot-password/verify', { body: { email, code: '12' } });
  await s.step('xác nhận đúng mã', 'f', 'POST', '/api/auth/staff/forgot-password/verify', { body: { email, code: '123456' }, save: (b) => ({ tok: b.resetToken }) });
  await s.step('đặt lại mật khẩu ngắn', 'f', 'POST', '/api/auth/staff/reset-password', { body: (v) => ({ token: v.tok, newPassword: '123' }) });
  await s.step('đặt lại mật khẩu token sai', 'f', 'POST', '/api/auth/staff/reset-password', { body: { token: 'sai', newPassword: 'MatKhau@789' } });
  await s.step('đặt lại mật khẩu', 'f', 'POST', '/api/auth/staff/reset-password', { body: (v) => ({ token: v.tok, newPassword: 'MatKhau@789' }) });
  await s.step('dùng lại token', 'f', 'POST', '/api/auth/staff/reset-password', { body: (v) => ({ token: v.tok, newPassword: 'MatKhau@000' }) });
  await s.step('đăng nhập mật khẩu cũ', 'n', 'POST', '/api/auth/staff/login', { body: { identifier: email.toLowerCase(), password: 'MatKhau@123' } });
  await s.step('đăng nhập mật khẩu mới', 'n', 'POST', '/api/auth/staff/login', { body: { identifier: email, password: 'MatKhau@789' } });
}

// ---- 7. Dữ liệu demo có sẵn (so cấu trúc và kiểu, không so giá trị vì dữ liệu giả ngẫu nhiên khác nhau)
{
  const s = new Scenario('dữ liệu demo');
  await s.step('người dùng mẫu đăng nhập', 'u', 'POST', '/api/auth/login', { body: { phone: '0912345678' }, save: (b) => ({ code: b.devCode }) });
  await s.step('xác nhận thiết bị', 'u', 'POST', '/api/auth/login/verify', { body: (v) => ({ phone: '0912345678', code: v.code }), mode: 'shape' });
  await s.step('me', 'u', 'GET', '/api/me', { mode: 'shape' });
  await s.step('hồ sơ khám', 'u', 'GET', '/api/me/patients', { mode: 'shape', save: (b) => ({ pid: b.items?.[0]?.id }) });
  await s.step('phiếu khám', 'u', 'GET', '/api/me/appointments', { mode: 'shape', save: (b) => ({ aid: b.items?.[0]?.id }) });
  await s.step('chi tiết phiếu', 'u', 'GET', (v) => `/api/me/appointments/${v.aid}`, { mode: 'shape' });
  await s.step('lịch sử khám', 'u', 'GET', '/api/me/history', { mode: 'shape' });
  await s.step('đăng nhập tiếp tân', 'l', 'POST', '/api/auth/staff/login', { body: { identifier: '0900000002', password: 'ChucTu@2026' } });
  await s.step('danh sách hồ sơ', 'l', 'GET', '/api/staff/patients?limit=3', { mode: 'shape', save: (b) => ({ ptId: b.items?.[0]?.id }) });
  await s.step('hồ sơ có lịch sử', 'l', 'GET', (v) => `/api/staff/patients/${v.ptId}`, { mode: 'shape' });
  await s.step('lịch trong ngày', 'l', 'GET', '/api/staff/appointments', { mode: 'shape', save: (b) => ({ dayId: b.items?.[0]?.id }) });
  await s.step('chi tiết một phiếu trong ngày', 'l', 'GET', (v) => `/api/staff/appointments/${v.dayId}`, { mode: 'shape', ignore: ['acupressure', 'followUpDate'] });
  await s.step('bảng điều khiển admin', 'ad', 'POST', '/api/auth/staff/login', { body: { identifier: 'admin@chuctu.vn', password: 'ChucTu@2026' } });
  await s.step('bảng điều khiển', 'ad', 'GET', '/api/admin/dashboard', { mode: 'shape' });
  await s.step('danh sách bài viết công khai', 'p', 'GET', '/api/posts?limit=2', { mode: 'shape' });
}

console.log('');
console.log(`Đã chạy ${results.steps} bước, ${results.failed.length} bước khác nhau (${results.diffs} điểm khác).`);
if (results.failed.length) {
  console.log('Các bước khác:');
  results.failed.forEach((f) => console.log('  ' + f));
}
process.exit(results.failed.length ? 1 : 0);
