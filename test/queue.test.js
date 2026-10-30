import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, describe, it } from 'node:test';
import { DEFAULT_NOW, reset, setup, staffAgent, teardown } from './helpers.js';
import { Appointment, Checkin, MedicalRecord, Patient, User } from '../src/models/index.js';
import { isReceivingTime, priorityGroupFor } from '../src/services/queue.service.js';
import { autoCancelNoShows } from '../src/services/appointment.service.js';
import { addDays, clock, slotDateValue, slotInstant, todayVN } from '../src/utils/time.js';

before(setup);
after(teardown);
beforeEach(reset);

let seq = 0;
/** Tạo phiếu hôm nay trực tiếp trong CSDL, không phụ thuộc giờ chạy kiểm thử. */
async function todayAppointment(name, { source = 'ONLINE', start = '08:00' } = {}) {
  seq += 1;
  const staff = await User.findOne({ role: 'RECEPTIONIST' });
  const patient = await Patient.create({ patientCode: `CT-BN-T-${seq}`, fullName: name, phone: `09160000${String(seq).padStart(2, '0')}` });
  const date = todayVN();
  return Appointment.create({
    ticketCode: `CT-PK-T-${seq}`,
    patientId: patient._id,
    serviceType: 'CHAM_CUU',
    slotDate: slotDateValue(date),
    slotStart: start,
    expectedTime: slotInstant(date, start),
    source,
    createdBy: staff._id,
    createdAt: new Date(Date.now() - 3 * 86400_000),
  });
}

describe('Giờ nhận bệnh 8:00 – 16:30', () => {
  const today = (hhmm) => slotInstant(todayVN(), hhmm);
  afterEach(() => {
    clock.now = DEFAULT_NOW;
  });

  it('chỉ trong giờ nhận bệnh mới là giờ check-in', () => {
    assert.equal(isReceivingTime(today('07:59')), false);
    assert.equal(isReceivingTime(today('08:00')), true);
    assert.equal(isReceivingTime(today('16:29')), true);
    assert.equal(isReceivingTime(today('16:30')), false);
    assert.equal(isReceivingTime(today('22:00')), false);
  });

  it('ngoài giờ nhận bệnh không check-in được, phiếu vẫn Chưa đến', async () => {
    const staff = await staffAgent();
    const a = await todayAppointment('Ngoài Giờ');
    for (const hhmm of ['22:00', '07:30', '16:30']) {
      clock.now = () => today(hhmm);
      const res = await staff.post(`/api/staff/appointments/${a._id}/checkin`).expect(409);
      assert.equal(res.body.error.code, 'CLOSED');
      assert.match(res.body.error.message, /8:00 – 16:30/);
    }
    assert.equal((await Appointment.findById(a._id)).status, 'BOOKED');
    assert.equal(await Checkin.countDocuments({ appointmentId: a._id }), 0);

    clock.now = () => today('16:29');
    await staff.post(`/api/staff/appointments/${a._id}/checkin`).expect(200);
  });
});

describe('Tự huỷ phiếu không đến sau 17:00', () => {
  const today = (hhmm) => slotInstant(todayVN(), hhmm);
  const setDay = (a, dateStr) => Appointment.updateOne({ _id: a._id }, { $set: { slotDate: slotDateValue(dateStr) } });

  it('trước 17:00 chưa huỷ, từ 17:00 phiếu hôm nay còn Chưa đến chuyển sang Đã huỷ', async () => {
    const a = await todayAppointment('Không Đến', { start: '09:00' });
    assert.equal(await autoCancelNoShows(today('16:59')), 0);
    assert.equal((await Appointment.findById(a._id)).status, 'BOOKED');

    assert.equal(await autoCancelNoShows(today('17:00')), 1);
    const after = await Appointment.findById(a._id);
    assert.equal(after.status, 'CANCELLED');
    assert.match(after.cancelReason, /Không đến khám/);
    assert.ok(after.cancelledAt);
    assert.equal(after.cancelledBy, null);
  });

  it('chỉ huỷ phiếu Chưa đến của hôm nay và các ngày trước, không đụng phiếu đã check-in hay ngày sau', async () => {
    const staff = await staffAgent();
    const checkedIn = await todayAppointment('Đã Đến');
    await staff.post(`/api/staff/appointments/${checkedIn._id}/checkin`).expect(200);
    const yesterday = await todayAppointment('Hôm Qua');
    await setDay(yesterday, addDays(todayVN(), -1));
    const tomorrow = await todayAppointment('Ngày Mai');
    await setDay(tomorrow, addDays(todayVN(), 1));

    // Phiếu ngày trước bị bỏ sót (server tắt lúc 17:00) được huỷ bù, kể cả buổi sáng
    assert.equal(await autoCancelNoShows(today('09:00')), 1);
    assert.equal((await Appointment.findById(yesterday._id)).status, 'CANCELLED');

    await autoCancelNoShows(today('17:30'));
    // Đã check-in (tự động mời có thể đưa luôn vào khám) thì không bị huỷ
    assert.notEqual((await Appointment.findById(checkedIn._id)).status, 'CANCELLED');
    assert.equal((await Appointment.findById(tomorrow._id)).status, 'BOOKED');
  });
});

describe('Nhóm ưu tiên trong hàng chờ (BR.28)', () => {
  const appt = (source, createdAt) => ({ slotDate: slotDateValue('2026-10-20'), slotStart: '09:00', source, createdAt });
  const at = (hhmm) => new Date(`2026-10-20T${hhmm}:00+07:00`);

  it('phiếu đặt trước, đến trong khung giờ thì ưu tiên', () => {
    assert.equal(priorityGroupFor(appt('ONLINE', at('07:00')), at('09:20')), 'PRIORITY');
    assert.equal(priorityGroupFor(appt('ONLINE', at('07:00')), at('08:50')), 'PRIORITY');
  });

  it('đến sau khung giờ hoặc quá sớm thì xếp nhóm thường', () => {
    assert.equal(priorityGroupFor(appt('ONLINE', at('07:00')), at('10:05')), 'NORMAL');
    assert.equal(priorityGroupFor(appt('ONLINE', at('07:00')), at('08:00')), 'NORMAL');
  });

  it('khách đến trực tiếp, đặt hộ trong ngày, xếp nhóm thường', () => {
    assert.equal(priorityGroupFor(appt('COUNTER', at('09:10')), at('09:15')), 'NORMAL');
  });
});

describe('Check-in và điều phối hàng đợi', () => {
  it('check-in rồi hoàn tác đưa phiếu về Chưa đến (BR.27)', async () => {
    const staff = await staffAgent();
    await staff.put('/api/staff/queue/auto-invite').send({ enabled: false }).expect(200);
    const a = await todayAppointment('Bệnh nhân A');
    const res = await staff.post(`/api/staff/appointments/${a._id}/checkin`).expect(200);
    assert.equal(res.body.appointment.status, 'CHECKED_IN');
    const undo = await staff.post(`/api/staff/appointments/${a._id}/undo-checkin`).expect(200);
    assert.equal(undo.body.appointment.status, 'BOOKED');
    const q = await staff.get('/api/staff/queue').expect(200);
    assert.equal(q.body.waitingCount, 0);
  });

  it('phiếu đã huỷ không check-in được (BR.24)', async () => {
    const staff = await staffAgent();
    const a = await todayAppointment('Bệnh nhân B');
    await staff.post(`/api/staff/appointments/${a._id}/cancel`).send({ reason: 'Không đến được' }).expect(200);
    await staff.post(`/api/staff/appointments/${a._id}/checkin`).expect(409);
  });

  it('tối đa 5 người trong phòng, tự động mời người kế tiếp khi còn ghế (BR.29)', async () => {
    const staff = await staffAgent();
    const appts = [];
    for (let i = 0; i < 7; i += 1) appts.push(await todayAppointment(`Người ${i}`));
    for (const a of appts) await staff.post(`/api/staff/appointments/${a._id}/checkin`).expect(200);

    let q = (await staff.get('/api/staff/queue').expect(200)).body;
    assert.equal(q.inRoomCount, 5);
    assert.equal(q.waitingCount, 2);

    // Lưu phiếu kết quả cho người ở ghế 1: hoàn thành, rời phòng, người kế tiếp vào (BR.30)
    const first = q.seats[0];
    await staff
      .post(`/api/staff/appointments/${first.appointmentId}/record`)
      .send({ diagnosis: 'Đau vai gáy', pulse: '78', bloodPressure: '120/80', doctorName: 'BS. Minh' })
      .expect(201);
    q = (await staff.get('/api/staff/queue').expect(200)).body;
    assert.equal(q.inRoomCount, 5);
    assert.equal(q.waitingCount, 1);
    assert.equal(q.completedCount, 1);
    const done = await staff.get(`/api/staff/appointments/${first.appointmentId}`).expect(200);
    assert.equal(done.body.appointment.status, 'COMPLETED');
  });

  it('nhóm ưu tiên đứng trên, trong nhóm ai check-in trước đứng trước', async () => {
    const staff = await staffAgent();
    await staff.put('/api/staff/queue/auto-invite').send({ enabled: false }).expect(200);
    const walkIn = await todayAppointment('Khách vãng lai', { source: 'COUNTER' });
    await Appointment.updateOne({ _id: walkIn._id }, { $set: { createdAt: new Date() } });
    const booked = await todayAppointment('Có phiếu đặt trước');
    await staff.post(`/api/staff/appointments/${walkIn._id}/checkin`).expect(200);
    await staff.post(`/api/staff/appointments/${booked._id}/checkin`).expect(200);
    // Nhóm của người có phiếu phụ thuộc giờ chạy kiểm thử, nên đặt cố định là đến đúng khung giờ
    await Checkin.updateOne({ appointmentId: booked._id }, { $set: { priorityGroup: 'PRIORITY' } });
    const q = (await staff.get('/api/staff/queue').expect(200)).body;
    assert.equal(q.waiting[0].patient.fullName, 'Có phiếu đặt trước');
    assert.equal(q.waiting.at(-1).patient.fullName, 'Khách vãng lai');
    assert.equal(q.waiting.at(-1).priorityGroup, 'NORMAL');
  });

  it('hoàn tác vào khám đưa người đó về hàng chờ, ghế trống nhận người kế tiếp (BR.27)', async () => {
    const staff = await staffAgent();
    const appts = [];
    for (let i = 0; i < 6; i += 1) appts.push(await todayAppointment(`Người ${i}`));
    for (const a of appts) await staff.post(`/api/staff/appointments/${a._id}/checkin`).expect(200);
    let q = (await staff.get('/api/staff/queue').expect(200)).body;
    const seat1 = q.seats[0];
    q = (await staff.post(`/api/staff/queue/${seat1.id}/undo-enter`).expect(200)).body;
    assert.equal(q.inRoomCount, 5);
    assert.equal(q.waitingCount, 1);
    assert.equal(q.waiting[0].id, seat1.id);
    assert.notEqual(q.seats[0].id, seat1.id);
  });

  it('sửa phiếu kết quả ghi lại lịch sử sửa (UC.9.9)', async () => {
    const staff = await staffAgent();
    const a = await todayAppointment('Người sửa phiếu');
    await staff.post(`/api/staff/appointments/${a._id}/checkin`).expect(200);
    const created = await staff.post(`/api/staff/appointments/${a._id}/record`).send({ diagnosis: 'Đau lưng' }).expect(201);
    const updated = await staff.put(`/api/staff/records/${created.body.record.id}`).send({ diagnosis: 'Thoát vị đĩa đệm nhẹ' }).expect(200);
    assert.equal(updated.body.record.editHistory.length, 1);
    assert.deepEqual(updated.body.record.editHistory[0].changes.diagnosis, { from: 'Đau lưng', to: 'Thoát vị đĩa đệm nhẹ' });
  });

  it('hồ sơ bị khoá không check-in và không sửa phiếu kết quả được (BR.38)', async () => {
    const staff = await staffAgent();
    const admin = await staffAgent('admin@chuctu.vn');
    const a = await todayAppointment('Người bị khoá');
    await admin.post(`/api/admin/patients/${a.patientId}/lock`).expect(200);
    await staff.post(`/api/staff/appointments/${a._id}/checkin`).expect(403);
    await staff.patch(`/api/staff/patients/${a.patientId}`).send({ fullName: 'Tên mới' }).expect(403);
    const detail = await staff.get(`/api/staff/patients/${a.patientId}`).expect(200);
    assert.equal(detail.body.patient.isLocked, true);
  });

  it('mời đúng một người vào khám và hoàn tác từ Lịch trong ngày', async () => {
    const staff = await staffAgent();
    await staff.put('/api/staff/queue/auto-invite').send({ enabled: false }).expect(200);
    const first = await todayAppointment('Người đầu');
    const second = await todayAppointment('Người sau');
    await staff.post(`/api/staff/appointments/${first._id}/checkin`).expect(200);
    await staff.post(`/api/staff/appointments/${second._id}/checkin`).expect(200);

    const entered = await staff.post(`/api/staff/appointments/${second._id}/enter`).expect(200);
    assert.equal(entered.body.appointment.status, 'IN_PROGRESS');
    const day = (await staff.get('/api/staff/appointments').expect(200)).body;
    const row = day.items.find((x) => x.id === second._id.toString());
    assert.equal(row.checkin.status, 'IN_ROOM');
    assert.ok(row.checkin.enteredAt);
    assert.equal(day.items.find((x) => x.id === first._id.toString()).checkin.queueNo, 1);

    const state = (await staff.get('/api/staff/queue').expect(200)).body;
    assert.equal(state.inRoomCount, 1);
    assert.equal(state.waitingCount, 1);

    const undone = await staff.post(`/api/staff/appointments/${second._id}/undo-enter`).expect(200);
    assert.equal(undone.body.appointment.status, 'CHECKED_IN');
    await staff.post(`/api/staff/appointments/${second._id}/undo-enter`).expect(409);
  });

  it('phiếu kết quả chỉ sửa được trong 24 giờ sau khi xác nhận', async () => {
    const staff = await staffAgent();
    const a = await todayAppointment('Người quá hạn sửa');
    await staff.post(`/api/staff/appointments/${a._id}/checkin`).expect(200);
    const created = await staff.post(`/api/staff/appointments/${a._id}/record`).send({ diagnosis: 'Đau gối' }).expect(201);
    assert.ok(created.body.record.editableUntil);
    // Ghi thẳng vào collection để mongoose không tự đặt lại createdAt
    const rec = await MedicalRecord.findById(created.body.record.id);
    await MedicalRecord.collection.updateOne({ _id: rec._id }, { $set: { createdAt: new Date(Date.now() - 25 * 3600_000) } });
    await staff.put(`/api/staff/records/${created.body.record.id}`).send({ diagnosis: 'Thoái hoá khớp gối' }).expect(403);

    const list = (await staff.get(`/api/staff/patients?q=${encodeURIComponent('Người quá hạn sửa')}`).expect(200)).body;
    assert.equal(list.items[0].lastVisitDate, todayVN());
    const dates = (await staff.get(`/api/staff/appointments/dates?month=${todayVN().slice(0, 7)}`).expect(200)).body;
    assert.ok(dates.dates.includes(todayVN()));
  });
});

describe('Mời vào khám (UC.10.2)', () => {
  it('hàng chờ trống thì báo chưa có ai', async () => {
    const staff = await staffAgent();
    const res = await staff.post('/api/staff/queue/invite').expect(409);
    assert.equal(res.body.error.code, 'QUEUE_EMPTY');
  });

  it('phòng đã đủ 5 người thì người tiếp theo vẫn chờ', async () => {
    const staff = await staffAgent();
    const people = [];
    for (let i = 0; i < 6; i += 1) people.push(await todayAppointment(`Người ${i}`));
    for (const a of people) await staff.post(`/api/staff/appointments/${a._id}/checkin`).expect(200);

    const res = await staff.post('/api/staff/queue/invite').expect(409);
    assert.equal(res.body.error.code, 'ROOM_FULL');
    const state = (await staff.get('/api/staff/queue').expect(200)).body;
    assert.equal(state.inRoomCount, 5);
    assert.equal(state.waitingCount, 1);
  });

  it('còn ghế thì mời đúng người đầu hàng chờ', async () => {
    const staff = await staffAgent();
    await staff.put('/api/staff/queue/auto-invite').send({ enabled: false }).expect(200);
    const first = await todayAppointment('Người đến trước');
    const second = await todayAppointment('Người đến sau');
    await staff.post(`/api/staff/appointments/${first._id}/checkin`).expect(200);
    await staff.post(`/api/staff/appointments/${second._id}/checkin`).expect(200);

    const state = (await staff.post('/api/staff/queue/invite').expect(200)).body;
    assert.equal(state.inRoomCount, 1);
    assert.equal((await Appointment.findById(first._id)).status, 'IN_PROGRESS');
    assert.equal((await Appointment.findById(second._id)).status, 'CHECKED_IN');
  });
});
