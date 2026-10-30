import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { slotTimeIssue } from '../src/services/appointment.service.js';
import { addDays, slotInstant } from '../src/utils/time.js';

const TODAY = '2026-11-10';
const at = (hhmm) => slotInstant(TODAY, hhmm);

describe('Quy tắc chọn khung giờ (BR.13, BR.14)', () => {
  it('người dùng không chọn được khung đã bắt đầu trong hôm nay', () => {
    const now = at('10:00');
    assert.equal(slotTimeIssue(TODAY, '09:00', 'USER', now), 'Khung giờ đã bắt đầu.');
    assert.equal(slotTimeIssue(TODAY, '10:00', 'USER', now), 'Khung giờ đã bắt đầu.');
    assert.equal(slotTimeIssue(TODAY, '11:00', 'USER', now), null);
    assert.equal(slotTimeIssue(TODAY, '10:00', 'USER', at('09:59')), null);
  });

  it('tiếp tân đặt hộ được khung đang diễn ra, không đặt được khung đã kết thúc', () => {
    assert.equal(slotTimeIssue(TODAY, '10:00', 'COUNTER', at('10:30')), null);
    assert.equal(slotTimeIssue(TODAY, '09:00', 'COUNTER', at('09:59')), null);
    assert.equal(slotTimeIssue(TODAY, '09:00', 'COUNTER', at('10:00')), 'Khung giờ đã kết thúc.');
  });

  it('người dùng đặt trước tối đa 30 ngày, tiếp tân tối đa 7 ngày', () => {
    const now = at('10:00');
    assert.equal(slotTimeIssue(addDays(TODAY, 30), '08:00', 'USER', now), null);
    assert.match(slotTimeIssue(addDays(TODAY, 31), '08:00', 'USER', now), /30 ngày/);
    assert.equal(slotTimeIssue(addDays(TODAY, 7), '08:00', 'COUNTER', now), null);
    assert.match(slotTimeIssue(addDays(TODAY, 8), '08:00', 'COUNTER', now), /7 ngày/);
  });

  it('không nhận ngày đã qua và khung giờ ngoài danh sách', () => {
    const now = at('10:00');
    assert.equal(slotTimeIssue(addDays(TODAY, -1), '08:00', 'USER', now), 'Ngày khám đã qua.');
    assert.equal(slotTimeIssue(addDays(TODAY, -1), '08:00', 'COUNTER', now), 'Ngày khám đã qua.');
    assert.equal(slotTimeIssue(addDays(TODAY, 1), '12:00', 'USER', now), 'Khung giờ không hợp lệ.');
  });
});
