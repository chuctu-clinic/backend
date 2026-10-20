import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { futureDate, registerUser, reset, selfPatientId, setup, staffAgent, teardown, type Agent } from './helpers.ts';

before(setup);
after(teardown);
beforeEach(reset);

const book = (agent: Agent, patientId: string, slotDate: string, slotStart = '09:00') =>
  agent.post('/api/me/appointments').send({ patientId, serviceType: 'CHAM_CUU', slotDate, slotStart });

describe('Đặt lịch khám', () => {
  it('đặt lịch xong có ngay phiếu khám với mã duy nhất (BR.15, BR.16)', async () => {
    const agent = await registerUser();
    const patientId = await selfPatientId(agent);
    const res = await book(agent, patientId, futureDate(2)).expect(201);
    const appt = res.body.appointment;
    assert.match(appt.ticketCode, /^CT-PK-\d{2}-\d{4}-\d{3}$/);
    assert.equal(appt.status, 'BOOKED');
    assert.equal(appt.slotStart, '09:00');
  });

  it('không đặt quá 30 ngày tới hoặc ngày đã qua (BR.13)', async () => {
    const agent = await registerUser();
    const patientId = await selfPatientId(agent);
    await book(agent, patientId, futureDate(31)).expect(400);
    await book(agent, patientId, futureDate(-1)).expect(400);
  });

  it('mỗi khung giờ nhận tối đa 6 phiếu, kể cả khi nhiều người đặt cùng lúc (BR.14, QAs6)', async () => {
    const date = futureDate(3);
    const agents = [];
    for (let i = 0; i < 9; i += 1) agents.push(await registerUser(`091200000${i}`, `Người ${i}`));
    const ids = await Promise.all(agents.map(selfPatientId));
    const results = await Promise.all(agents.map((a, i) => book(a, ids[i], date, '10:00')));
    const ok = results.filter((r) => r.status === 201).length;
    const full = results.filter((r) => r.status === 409 && r.body.error.code === 'SLOT_FULL').length;
    assert.equal(ok, 6);
    assert.equal(full, 3);

    const avail = await agents[0].get(`/api/me/availability?date=${date}`).expect(200);
    const slot = avail.body.slots.find((s: { start: string }) => s.start === '10:00');
    assert.equal(slot.available, false);
    assert.equal(slot.reason, 'Đã đủ chỗ');
  });

  it('huỷ phiếu trả lại chỗ cho khung giờ và đặt lại được (BR.18)', async () => {
    const date = futureDate(4);
    const agents = [];
    for (let i = 0; i < 6; i += 1) agents.push(await registerUser(`091300000${i}`, `Người ${i}`));
    const tickets = [];
    for (const a of agents) tickets.push((await book(a, await selfPatientId(a), date, '11:00').expect(201)).body.appointment);

    const extra = await registerUser('0913000099');
    const extraId = await selfPatientId(extra);
    await book(extra, extraId, date, '11:00').expect(409);

    const cancelled = await agents[0].post(`/api/me/appointments/${tickets[0].id}/cancel`).send({ reason: 'Bận việc' }).expect(200);
    assert.equal(cancelled.body.appointment.status, 'CANCELLED');
    await book(extra, extraId, date, '11:00').expect(201);
  });

  it('chỉ xem được phiếu khám của mình (BR.20)', async () => {
    const a = await registerUser('0914000001');
    const b = await registerUser('0914000002');
    const appt = (await book(a, await selfPatientId(a), futureDate(2)).expect(201)).body.appointment;
    await b.get(`/api/me/appointments/${appt.id}`).expect(404);
    await b.post(`/api/me/appointments/${appt.id}/cancel`).send({}).expect(404);
  });

  it('mỗi tài khoản tối đa 10 hồ sơ khám (BR.12)', async () => {
    const agent = await registerUser();
    for (let i = 0; i < 9; i += 1) {
      await agent
        .post('/api/me/patients')
        .send({ fullName: `Người thân ${i}`, dob: '1990-01-01', gender: 'NAM', relationship: 'CON' })
        .expect(201);
    }
    const res = await agent
      .post('/api/me/patients')
      .send({ fullName: 'Người thứ 11', dob: '1990-01-01', gender: 'NAM', relationship: 'CON' })
      .expect(400);
    assert.equal(res.body.error.code, 'PROFILE_LIMIT');
  });

  it('hồ sơ bị khoá không đặt lịch được (BR.19)', async () => {
    const agent = await registerUser();
    const patientId = await selfPatientId(agent);
    const admin = await staffAgent('admin@chuctu.vn');
    await admin.post(`/api/admin/patients/${patientId}/lock`).expect(200);
    const res = await book(agent, patientId, futureDate(2)).expect(403);
    assert.match(res.body.error.message, /khoá/);
  });

  it('người dùng chỉ thấy trạng thái Đã đặt lịch dù phiếu đã check-in (BR.17)', async () => {
    const agent = await registerUser();
    const patientId = await selfPatientId(agent);
    const staff = await staffAgent();
    const patient = await staff.get(`/api/staff/patients/${patientId}`).expect(200);
    const counter = await staff
      .post('/api/staff/appointments')
      .send({ patientId: patient.body.patient.id, serviceType: 'CHAM_CUU', slotDate: futureDate(1), slotStart: '09:00' })
      .expect(201);
    const mine = await agent.get(`/api/me/appointments/${counter.body.appointment.id}`).expect(200);
    assert.equal(mine.body.appointment.status, 'BOOKED');
  });
});

describe('Đặt hộ và đổi giờ tại quầy', () => {
  it('tiếp tân tạo hồ sơ được cảnh báo trùng số điện thoại (BR.25, BR.35)', async () => {
    const staff = await staffAgent();
    await staff.post('/api/staff/patients').send({ fullName: 'Lê Văn B', birthYear: 1960, phone: '0915000001' }).expect(201);
    const dup = await staff.post('/api/staff/patients').send({ fullName: 'Lê Văn C', birthYear: 1961, phone: '0915000001' }).expect(409);
    assert.equal(dup.body.error.code, 'DUPLICATE_PHONE');
    assert.equal(dup.body.error.duplicates.length, 1);
    await staff
      .post('/api/staff/patients')
      .send({ fullName: 'Lê Văn C', birthYear: 1961, phone: '0915000001', confirmDuplicate: true })
      .expect(201);
  });

  it('tiếp tân chỉ đặt hộ trong vòng 7 ngày (BR.13)', async () => {
    const staff = await staffAgent();
    const p = await staff.post('/api/staff/patients').send({ fullName: 'Lê Văn D', birthYear: 1960, phone: '0915000002' }).expect(201);
    const send = (date: string) =>
      staff.post('/api/staff/appointments').send({ patientId: p.body.patient.id, serviceType: 'BOC_THUOC', slotDate: date, slotStart: '15:00' });
    await send(futureDate(8)).expect(400);
    const ok = await send(futureDate(7)).expect(201);
    assert.equal(ok.body.appointment.source, 'COUNTER');
  });

  it('đổi giờ huỷ phiếu cũ và tạo phiếu mới trỏ về phiếu cũ (BR.26)', async () => {
    const staff = await staffAgent();
    const p = await staff.post('/api/staff/patients').send({ fullName: 'Lê Văn E', birthYear: 1960, phone: '0915000003' }).expect(201);
    const old = await staff
      .post('/api/staff/appointments')
      .send({ patientId: p.body.patient.id, serviceType: 'CHAM_CUU', slotDate: futureDate(2), slotStart: '08:00' })
      .expect(201);
    const res = await staff
      .post(`/api/staff/appointments/${old.body.appointment.id}/reschedule`)
      .send({ slotDate: futureDate(3), slotStart: '13:00' })
      .expect(200);
    assert.equal(res.body.appointment.rescheduledFromId, old.body.appointment.id);
    const prev = await staff.get(`/api/staff/appointments/${old.body.appointment.id}`).expect(200);
    assert.equal(prev.body.appointment.status, 'CANCELLED');
  });

  it('huỷ tại quầy phải có lý do (BR.26)', async () => {
    const staff = await staffAgent();
    const p = await staff.post('/api/staff/patients').send({ fullName: 'Lê Văn F', birthYear: 1960, phone: '0915000004' }).expect(201);
    const appt = await staff
      .post('/api/staff/appointments')
      .send({ patientId: p.body.patient.id, serviceType: 'CHAM_CUU', slotDate: futureDate(2), slotStart: '08:00' })
      .expect(201);
    await staff.post(`/api/staff/appointments/${appt.body.appointment.id}/cancel`).send({}).expect(400);
    await staff.post(`/api/staff/appointments/${appt.body.appointment.id}/cancel`).send({ reason: 'Đặt nhầm' }).expect(200);
  });
});
