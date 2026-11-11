-- Thêm vai trò Bác sĩ và Kỹ thuật viên, và quy trình khám mới của một lượt check-in:
--   WAITING            chờ bác sĩ gọi khám
--   EXAMINING          bác sĩ đang khám sơ bộ (doctor_id)
--   WAITING_TREATMENT  bác sĩ đã chỉ định điều trị, chờ ghế điều trị
--   IN_ROOM            đang ngồi ghế điều trị (seat_no 1..5); giữ tên cũ để ràng buộc một ghế một người không đổi
--   WAITING_CONCLUSION kỹ thuật viên đã lưu báo cáo, ghế đã trả, chờ bác sĩ kết luận
--   COMPLETED          bác sĩ đã lưu phiếu kết quả
--   REVERTED           hoàn tác check-in hoặc huỷ phiếu

ALTER TABLE users DROP CHECK ck_users_role;
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'RECEPTIONIST', 'DOCTOR', 'TECHNICIAN', 'ADMIN'));

ALTER TABLE checkins DROP CHECK ck_checkins_status;
ALTER TABLE checkins DROP INDEX uq_checkin_active;
ALTER TABLE checkins DROP COLUMN active_appointment_id;

ALTER TABLE checkins
  -- Khám sơ bộ (bác sĩ)
  ADD COLUMN doctor_id BIGINT NULL,
  ADD COLUMN exam_started_at DATETIME(3) NULL,
  ADD COLUMN symptoms TEXT NULL,
  ADD COLUMN prelim_diagnosis TEXT NULL,
  ADD COLUMN prelim_pulse VARCHAR(500) NULL,
  ADD COLUMN prelim_blood_pressure VARCHAR(500) NULL,
  -- Chỉ định điều trị: các kỹ thuật (mã cách nhau bởi dấu phẩy) và ghi chú cho kỹ thuật viên
  ADD COLUMN treatment_kinds VARCHAR(200) NULL,
  ADD COLUMN treatment_order TEXT NULL,
  ADD COLUMN ordered_at DATETIME(3) NULL,
  -- Báo cáo điều trị (kỹ thuật viên)
  ADD COLUMN technician_id BIGINT NULL,
  ADD COLUMN report_acupuncture TEXT NULL,
  ADD COLUMN report_acupressure TEXT NULL,
  ADD COLUMN report_other TEXT NULL,
  ADD COLUMN report_note TEXT NULL,
  ADD COLUMN treated_at DATETIME(3) NULL,
  -- Mỗi phiếu có tối đa một check-in đang hiệu lực
  ADD COLUMN active_appointment_id BIGINT GENERATED ALWAYS AS
    (IF(status IN ('WAITING', 'EXAMINING', 'WAITING_TREATMENT', 'IN_ROOM', 'WAITING_CONCLUSION', 'COMPLETED'), appointment_id, NULL)) STORED,
  ADD UNIQUE KEY uq_checkin_active (active_appointment_id),
  ADD KEY ix_checkins_doctor (doctor_id),
  ADD KEY ix_checkins_technician (technician_id),
  ADD CONSTRAINT fk_checkins_doctor FOREIGN KEY (doctor_id) REFERENCES users (id),
  ADD CONSTRAINT fk_checkins_technician FOREIGN KEY (technician_id) REFERENCES users (id),
  ADD CONSTRAINT ck_checkins_status CHECK (status IN
    ('WAITING', 'EXAMINING', 'WAITING_TREATMENT', 'IN_ROOM', 'WAITING_CONCLUSION', 'COMPLETED', 'REVERTED'));
