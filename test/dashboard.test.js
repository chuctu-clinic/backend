import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { app, registerUser, request, reset, setup, staffAgent, teardown } from './helpers.js';
import { Appointment, Patient, User } from '../src/models/index.js';
import { slotDateValue, slotInstant, todayVN } from '../src/utils/time.js';

before(setup);
after(teardown);
beforeEach(reset);

let seq = 0;
async function todayAppointment(status = 'BOOKED') {
  seq += 1;
  const staff = await User.findOne({ role: 'RECEPTIONIST' });
  const patient = await Patient.create({ patientCode: `CT-BN-D-${seq}`, fullName: `Người ${seq}`, phone: `09210000${String(seq).padStart(2, '0')}` });
  const date = todayVN();
  return Appointment.create({
    ticketCode: `CT-PK-D-${seq}`,
    patientId: patient._id,
    serviceType: 'CHAM_CUU',
    slotDate: slotDateValue(date),
    slotStart: '08:00',
    expectedTime: slotInstant(date, '08:00'),
    source: 'COUNTER',
    status,
    createdBy: staff._id,
  });
}

describe('Tổng quan của Admin (UC.2.3)', () => {
  it('chỉ Admin xem được', async () => {
    await request(app).get('/api/admin/dashboard').expect(401);
    await (await staffAgent()).get('/api/admin/dashboard').expect(403);
    await (await registerUser()).get('/api/admin/dashboard').expect(403);
  });

  it('đếm phiếu trong ngày, phiếu huỷ không tính là còn hiệu lực', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    await todayAppointment();
    await todayAppointment();
    await todayAppointment('CANCELLED');

    const { appointments, date } = (await admin.get('/api/admin/dashboard').expect(200)).body;
    assert.equal(date, todayVN());
    assert.equal(appointments.total, 3);
    assert.equal(appointments.active, 2);
    assert.deepEqual(appointments.byStatus, { BOOKED: 2, CANCELLED: 1 });
  });

  it('đếm bài viết theo trạng thái và liệt kê bản nháp, không kèm nội dung', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const before = (await admin.get('/api/admin/dashboard').expect(200)).body;
    await admin.post('/api/admin/posts').send({ title: 'Bản nháp mới', category: 'TIN_TUC', content: '<p>Nháp</p>', status: 'DRAFT' }).expect(201);

    const { posts, drafts } = (await admin.get('/api/admin/dashboard').expect(200)).body;
    assert.equal(posts.draft, before.posts.draft + 1);
    assert.equal(posts.published, before.posts.published);
    assert.equal(drafts[0].title, 'Bản nháp mới');
    assert.equal(drafts[0].content, undefined);
  });

  it('đếm tài khoản theo vai trò và tóm tắt hàng đợi', async () => {
    const admin = await staffAgent('admin@chuctu.vn');
    const staff = await staffAgent();
    await registerUser();
    const appt = await todayAppointment();
    await staff.post(`/api/staff/appointments/${appt._id}/checkin`).expect(200);

    const { accounts, queue, patients } = (await admin.get('/api/admin/dashboard').expect(200)).body;
    assert.equal(accounts.USER, 1);
    assert.equal(accounts.ADMIN, 1);
    assert.equal(patients.total, 2);
    assert.equal(queue.inRoomCount, 1);
    assert.equal(queue.capacity, 5);
    assert.ok(Array.isArray(queue.slotMap));
  });
});
