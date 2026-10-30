import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { futureDate, registerUser, reset, selfPatientId, setup, staffAgent, teardown } from './helpers.js';
import { Appointment, Patient, User } from '../src/models/index.js';
import { slotDateValue, slotInstant, todayVN } from '../src/utils/time.js';

before(setup);
after(teardown);
beforeEach(reset);

const book = (agent, patientId, slotDate, extra = {}) =>
  agent.post('/api/me/appointments').send({ patientId, serviceType: 'CHAM_CUU', slotDate, slotStart: '09:00', ...extra });

describe('Danh sách phiếu khám của người dùng (UC.6.4)', () => {
  it('lọc theo trạng thái và chỉ có phiếu của tài khoản mình', async () => {
    const user = await registerUser('0919000001', 'Người Một');
    const other = await registerUser('0919000002', 'Người Hai');
    const patientId = await selfPatientId(user);
    const kept = (await book(user, patientId, futureDate(2)).expect(201)).body.appointment;
    const dropped = (await book(user, patientId, futureDate(3)).expect(201)).body.appointment;
    await user.post(`/api/me/appointments/${dropped.id}/cancel`).send({}).expect(200);
    await book(other, await selfPatientId(other), futureDate(2)).expect(201);

    const ids = async (qs = '') => (await user.get(`/api/me/appointments${qs}`).expect(200)).body.items.map((a) => a.id);
    assert.deepEqual((await ids()).sort(), [kept.id, dropped.id].sort());
    assert.deepEqual(await ids('?status=BOOKED'), [kept.id]);
    assert.deepEqual(await ids('?status=CANCELLED'), [dropped.id]);
    assert.deepEqual(await ids('?status=COMPLETED'), []);
  });

  it('đánh dấu phiếu đã có kết quả khám', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const patientId = await selfPatientId(user);
    const receptionist = await User.findOne({ role: 'RECEPTIONIST' });
    const patient = await Patient.findById(patientId);
    const date = todayVN();
    const appt = await Appointment.create({
      ticketCode: 'CT-PK-U-1',
      userId: patient.userId,
      patientId,
      serviceType: 'CHAM_CUU',
      slotDate: slotDateValue(date),
      slotStart: '08:00',
      expectedTime: slotInstant(date, '08:00'),
      source: 'ONLINE',
      createdBy: receptionist._id,
    });
    await staff.post(`/api/staff/appointments/${appt._id}/checkin`).expect(200);

    const hasResult = async () => (await user.get('/api/me/appointments').expect(200)).body.items[0].hasResult;
    assert.equal(await hasResult(), false);
    await staff.post(`/api/staff/appointments/${appt._id}/record`).send({ diagnosis: 'Đau vai' }).expect(201);
    assert.equal(await hasResult(), true);
  });
});

describe('Điều kiện đặt lịch (UC.6.3)', () => {
  it('một người chỉ có một phiếu trong ngày', async () => {
    const user = await registerUser();
    const patientId = await selfPatientId(user);
    const date = futureDate(2);
    await book(user, patientId, date).expect(201);
    const res = await book(user, patientId, date, { slotStart: '10:00' }).expect(409);
    assert.equal(res.body.error.code, 'ALREADY_BOOKED');
  });

  it('hồ sơ chưa khai quan hệ thì chưa đặt lịch được', async () => {
    const user = await registerUser();
    const patientId = await selfPatientId(user);
    await Patient.updateOne({ _id: patientId }, { $set: { relationship: null } });
    const res = await book(user, patientId, futureDate(2)).expect(400);
    assert.ok(res.body.error.fields.relationship);
  });

  it('đặt lại từ phiếu đã huỷ thì ghi nhớ phiếu cũ', async () => {
    const user = await registerUser();
    const patientId = await selfPatientId(user);
    const old = (await book(user, patientId, futureDate(2)).expect(201)).body.appointment;
    await user.post(`/api/me/appointments/${old.id}/cancel`).send({}).expect(200);

    const next = (await book(user, patientId, futureDate(3), { rebookFromId: old.id }).expect(201)).body.appointment;
    assert.equal((await Appointment.findById(next.id)).rescheduledFromId.toString(), old.id);
  });

  it('không ghi nhớ phiếu cũ nếu phiếu đó chưa huỷ hoặc của tài khoản khác', async () => {
    const owner = await registerUser('0919000011', 'Chủ Phiếu');
    const other = await registerUser('0919000012', 'Người Khác');
    const active = (await book(owner, await selfPatientId(owner), futureDate(2)).expect(201)).body.appointment;
    const cancelled = (await book(owner, await selfPatientId(owner), futureDate(3)).expect(201)).body.appointment;
    await owner.post(`/api/me/appointments/${cancelled.id}/cancel`).send({}).expect(200);

    const otherId = await selfPatientId(other);
    const a = (await book(other, otherId, futureDate(4), { rebookFromId: cancelled.id }).expect(201)).body.appointment;
    assert.equal((await Appointment.findById(a.id)).rescheduledFromId, null);
    const b = (await book(owner, await selfPatientId(owner), futureDate(5), { rebookFromId: active.id }).expect(201)).body.appointment;
    assert.equal((await Appointment.findById(b.id)).rescheduledFromId, null);
  });
});

describe('Tra cứu phiếu ở Lịch trong ngày (UC.7.1, UC.7.2)', () => {
  it('tìm theo mã phiếu, tên, số điện thoại trong ngày đang xem', async () => {
    const staff = await staffAgent();
    const a = await registerUser('0919000021', 'Phạm Văn Tìm');
    const b = await registerUser('0919000022', 'Đỗ Thị Khác');
    const date = futureDate(2);
    const ticket = (await book(a, await selfPatientId(a), date).expect(201)).body.appointment;
    await book(b, await selfPatientId(b), date).expect(201);

    const search = async (q) => (await staff.get(`/api/staff/appointments?date=${date}&q=${encodeURIComponent(q)}`).expect(200)).body.items.map((i) => i.id);
    assert.deepEqual(await search('Phạm Văn'), [ticket.id]);
    assert.deepEqual(await search('0919000021'), [ticket.id]);
    assert.deepEqual(await search(ticket.ticketCode), [ticket.id]);
    assert.deepEqual(await search('Không có ai'), []);
    // Ký tự đặc biệt trong ô tìm kiếm không được làm hỏng truy vấn
    assert.deepEqual(await search('(['), []);
  });

  it('tra mã phiếu ở quầy, mã không có thì trả về rỗng, mã quá ngắn thì báo lỗi', async () => {
    const staff = await staffAgent();
    const user = await registerUser();
    const ticket = (await book(user, await selfPatientId(user), futureDate(2)).expect(201)).body.appointment;
    const found = (await staff.get(`/api/staff/appointments/lookup?code=${ticket.ticketCode}`).expect(200)).body.appointment;
    assert.equal(found.id, ticket.id);
    assert.equal((await staff.get('/api/staff/appointments/lookup?code=CT-PK-00-0000-000').expect(200)).body.appointment, null);
    await staff.get('/api/staff/appointments/lookup?code=x').expect(400);
  });

  it('khung giờ ở quầy dùng quy tắc đặt hộ', async () => {
    const staff = await staffAgent();
    const { slots } = (await staff.get(`/api/staff/availability?date=${futureDate(2)}`).expect(200)).body;
    assert.ok(slots.every((s) => s.available));
    const far = await staff.get(`/api/staff/availability?date=${futureDate(8)}`).expect(200);
    assert.ok(far.body.slots.every((s) => !s.available));
  });
});
