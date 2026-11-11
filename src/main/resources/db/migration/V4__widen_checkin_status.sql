-- Trạng thái mới WAITING_TREATMENT, WAITING_CONCLUSION dài hơn 10 ký tự.
-- Cột sinh active_appointment_id và in_room_seat_no đọc cột status nên phải bỏ ra rồi tạo lại khi đổi kiểu cột.
ALTER TABLE checkins DROP INDEX uq_checkin_active, DROP INDEX uq_checkin_seat;
ALTER TABLE checkins DROP COLUMN active_appointment_id, DROP COLUMN in_room_seat_no;
ALTER TABLE checkins MODIFY status VARCHAR(20) NOT NULL DEFAULT 'WAITING';
ALTER TABLE checkins
  ADD COLUMN active_appointment_id BIGINT GENERATED ALWAYS AS
    (IF(status IN ('WAITING', 'EXAMINING', 'WAITING_TREATMENT', 'IN_ROOM', 'WAITING_CONCLUSION', 'COMPLETED'), appointment_id, NULL)) STORED,
  ADD COLUMN in_room_seat_no TINYINT GENERATED ALWAYS AS (IF(status = 'IN_ROOM', seat_no, NULL)) STORED,
  ADD UNIQUE KEY uq_checkin_active (active_appointment_id),
  ADD UNIQUE KEY uq_checkin_seat (visit_date, in_room_seat_no);
