import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { reset, setup, staffAgent, teardown } from './helpers.ts';
import { Appointment, Checkin, Patient, User, type AppointmentSource } from '../src/models/index.ts';
import { priorityGroupFor } from '../src/services/queue.service.ts';
import { slotDateValue, slotInstant, todayVN } from '../src/utils/time.ts';

before(setup);
after(teardown);
beforeEach(reset);

let seq = 0;
/** Tạo phiếu hôm nay trực tiếp trong CSDL, không phụ thuộc giờ chạy kiểm thử. */
async function todayAppointment(name: string, { source = 'ONLINE' as AppointmentSource, start = '08:00' } = {}) {
  seq += 1;
  const staff = await User.findOne({ role: 'RECEPTIONIST' }).orFail();
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

describe('Nhóm ưu tiên trong hàng chờ (BR.28)', () => {
  const appt = (source: AppointmentSource, createdAt: Date) => ({ slotDate: slotDateValue('2026-10-20'), slotStart: '09:00', source, createdAt });
  const at = (hhmm: string) => new Date(`2026-10-20T${hhmm}:00+07:00`);

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
});
