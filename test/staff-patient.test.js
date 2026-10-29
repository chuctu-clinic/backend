import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, it } from 'node:test';
import { reset, setup, staffAgent, teardown } from './helpers.js';
import { Patient } from '../src/models/index.js';

before(setup);
after(teardown);
beforeEach(reset);

const newPatient = () =>
  Patient.create({ patientCode: 'CT-BN-TEST-1', fullName: 'Nguyễn Văn Thử', phone: '0916000001', birthYear: 1950, gender: 'NAM', address: 'Phường 25, TP. Hồ Chí Minh' });

describe('Tiếp tân sửa thông tin hồ sơ bệnh nhân', () => {
  it('sửa đủ các trường như lúc người dùng tạo hồ sơ: ngày sinh, giới tính, địa chỉ, CCCD, email', async () => {
    const staff = await staffAgent();
    const p = await newPatient();
    const res = await staff
      .patch(`/api/staff/patients/${p._id}`)
      .send({
        fullName: 'Nguyễn Văn Sửa',
        phone: '0916000002',
        dob: '1952-03-12',
        gender: 'NU',
        address: '57/8 Điện Biên Phủ, Phường Thạnh Mỹ Tây, TP. Hồ Chí Minh',
        nationalId: '079052000123',
        email: 'Sua@Email.com',
      })
      .expect(200);
    const after = res.body.patient;
    assert.equal(after.fullName, 'Nguyễn Văn Sửa');
    assert.equal(after.phone, '0916000002');
    assert.equal(String(after.dob).slice(0, 10), '1952-03-12');
    assert.equal(after.birthYear, 1952);
    assert.equal(after.gender, 'NU');
    assert.equal(after.address, '57/8 Điện Biên Phủ, Phường Thạnh Mỹ Tây, TP. Hồ Chí Minh');
    assert.equal(after.nationalId, '079052000123');
    assert.equal(after.email, 'sua@email.com');
  });

  it('chỉ gửi vài trường thì các trường còn lại giữ nguyên (không bị xoá)', async () => {
    const staff = await staffAgent();
    const p = await newPatient();
    await Patient.updateOne({ _id: p._id }, { nationalId: '079052000123', email: 'giu@email.com' });
    const res = await staff.patch(`/api/staff/patients/${p._id}`).send({ fullName: 'Tên Mới' }).expect(200);
    assert.equal(res.body.patient.fullName, 'Tên Mới');
    assert.equal(res.body.patient.phone, '0916000001');
    assert.equal(res.body.patient.nationalId, '079052000123');
    assert.equal(res.body.patient.email, 'giu@email.com');
  });

  it('CCCD và email sai định dạng thì báo lỗi theo trường', async () => {
    const staff = await staffAgent();
    const p = await newPatient();
    const res = await staff.patch(`/api/staff/patients/${p._id}`).send({ nationalId: '123', email: 'khong-dung' }).expect(400);
    assert.ok(res.body.error.fields.nationalId);
    assert.ok(res.body.error.fields.email);
  });
});
