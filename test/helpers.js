process.env.NODE_ENV = 'test';
process.env.SMS_PROVIDER = 'console';

const { default: mongoose } = await import('mongoose');
const { default: request } = await import('supertest');
const { createApp } = await import('../src/app.js');
const { connectDb, disconnectDb } = await import('../src/db.js');
const { seed } = await import('../src/seed.js');

export { request };
export const app = createApp();

export async function setup() {
  await connectDb('');
  await seed();
}

export async function teardown() {
  await disconnectDb();
}

export async function reset() {
  const collections = await mongoose.connection.db.collections();
  for (const c of collections) await c.deleteMany({});
  await seed();
}

/** Ngày trong tương lai theo giờ Việt Nam. */
export function futureDate(days) {
  const d = new Date(Date.now() + 7 * 3600_000 + days * 86400_000);
  return d.toISOString().slice(0, 10);
}

/** Đăng ký một người dùng mới và trả về agent đã đăng nhập. */
export async function registerUser(phone = '0911000001', fullName = 'Nguyễn Văn A') {
  const agent = request.agent(app);
  const res = await agent
    .post('/api/auth/register')
    .send({ fullName, phone, dob: '1960-01-01', gender: 'NAM', address: 'Bình Thạnh' })
    .expect(200);
  await agent.post('/api/auth/register/verify').send({ phone, code: res.body.devCode }).expect(201);
  return agent;
}

export async function staffAgent(identifier = 'letan@chuctu.vn') {
  const agent = request.agent(app);
  await agent.post('/api/auth/staff/login').send({ identifier, password: 'ChucTu@2026' }).expect(200);
  return agent;
}

export async function selfPatientId(agent) {
  const res = await agent.get('/api/me/patients').expect(200);
  return res.body.items.find((p) => p.relationship === 'BAN_THAN').id;
}
