import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { normalizePhone, phoneDigits, phoneInputError, phoneProblem, toPhone } from '../src/shared/phone.js';

describe('Chuẩn hoá số điện thoại', () => {
  it('bỏ khoảng trắng, dấu chấm, gạch và đưa +84, 84 về số 0', () => {
    assert.equal(normalizePhone('0912 345 678'), '0912345678');
    assert.equal(normalizePhone('0912.345-678'), '0912345678');
    assert.equal(normalizePhone('(0912) 345678'), '0912345678');
    assert.equal(normalizePhone('+84912345678'), '0912345678');
    assert.equal(normalizePhone('84912345678'), '0912345678');
  });

  it('chỉ đổi đầu 84 khi đủ 11 số, không phải chuỗi thì giữ nguyên', () => {
    assert.equal(normalizePhone('8491234567'), '8491234567');
    assert.equal(normalizePhone(undefined), undefined);
    assert.equal(normalizePhone(912345678), 912345678);
  });
});

describe('Kiểm tra số điện thoại (BR.01)', () => {
  it('nhận số di động 10 chữ số với các đầu số 03, 05, 07, 08, 09', () => {
    for (const phone of ['0312345678', '0512345678', '0712345678', '0812345678', '0912345678', '+84912345678', '0912 345 678']) {
      assert.equal(phoneProblem(phone), null, phone);
    }
  });

  it('báo đúng lý do cho từng kiểu sai', () => {
    assert.match(phoneProblem('09a2345678'), /chỉ gồm chữ số/);
    assert.match(phoneProblem('09123'), /còn thiếu 5 chữ số/);
    assert.match(phoneProblem('09123456789'), /bị thừa 1 chữ số/);
    assert.match(phoneProblem('0212345678'), /Đầu số 02 không phải đầu số di động/);
  });

  it('ô trống chỉ báo lỗi khi bắt buộc', () => {
    assert.match(phoneProblem(''), /vui lòng nhập số điện thoại/);
    assert.match(phoneProblem(null), /vui lòng nhập số điện thoại/);
    assert.equal(phoneProblem('', { required: false }), null);
  });
});

describe('Ô số điện thoại có sẵn tiền tố +84', () => {
  it('lấy chữ số, bỏ phần 84 khi dán cả số quốc tế', () => {
    assert.equal(phoneDigits('+84 912 345 678'), '912345678');
    assert.equal(phoneDigits('84912345678'), '912345678');
    assert.equal(phoneDigits('0912-345-678'), '0912345678');
    assert.equal(phoneDigits(undefined), '');
  });

  it('cho gõ dư tối đa 12 số để báo thừa thay vì cắt bớt', () => {
    assert.equal(phoneDigits('0912345678901234'), '091234567890');
    assert.match(phoneInputError('09123456789'), /bị thừa 1 chữ số/);
  });

  it('9 số sau +84 được thêm số 0, cùng quy tắc với server', () => {
    assert.equal(toPhone('912345678'), '0912345678');
    assert.equal(toPhone('0912345678'), '0912345678');
    assert.equal(phoneInputError('912345678'), null);
    assert.equal(phoneInputError('', { required: false }), null);
  });
});
