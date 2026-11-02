-- Cấu trúc bảng của Phòng khám Chúc Từ (chuyển từ các model Mongoose trong backend/src/models).
-- InnoDB, utf8mb4, collation utf8mb4_0900_as_ci: không phân biệt hoa thường, có phân biệt dấu ("Nguyễn" khác "Nguyen").
-- Thời điểm: DATETIME(3) theo UTC. Ngày không có giờ: DATE.

CREATE TABLE users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  phone VARCHAR(20) NOT NULL,
  email VARCHAR(255) NULL,
  password_hash VARCHAR(100) NULL,
  must_change_password TINYINT(1) NOT NULL DEFAULT 0,
  full_name VARCHAR(255) NOT NULL,
  dob DATE NULL,
  gender VARCHAR(10) NULL,
  address VARCHAR(500) NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'USER',
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  last_login_at DATETIME(3) NULL,
  -- Lần cuối đổi thông tin cá nhân; không dùng updated_at vì mỗi lần đăng nhập nó cũng đổi
  info_updated_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_users_phone (phone),
  -- Email nếu có thì phải duy nhất: UNIQUE bỏ qua NULL nên nhiều tài khoản không email vẫn được
  UNIQUE KEY uq_users_email (email),
  KEY ix_users_role_created (role, created_at),
  CONSTRAINT ck_users_role CHECK (role IN ('USER', 'RECEPTIONIST', 'ADMIN')),
  CONSTRAINT ck_users_gender CHECK (gender IS NULL OR gender IN ('NAM', 'NU', 'KHAC'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

CREATE TABLE patients (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_code VARCHAR(30) NOT NULL,
  user_id BIGINT NULL,
  relationship VARCHAR(20) NULL,
  full_name VARCHAR(255) NOT NULL,
  dob DATE NULL,
  birth_year INT NULL,
  gender VARCHAR(10) NULL,
  phone VARCHAR(20) NULL,
  address VARCHAR(500) NULL,
  national_id VARCHAR(30) NULL,
  email VARCHAR(255) NULL,
  is_locked TINYINT(1) NOT NULL DEFAULT 0,
  locked_at DATETIME(3) NULL,
  created_by BIGINT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_patients_code (patient_code),
  KEY ix_patients_user (user_id),
  KEY ix_patients_phone (phone),
  KEY ix_patients_created_by (created_by),
  CONSTRAINT fk_patients_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_patients_created_by FOREIGN KEY (created_by) REFERENCES users (id),
  CONSTRAINT ck_patients_relationship CHECK (relationship IS NULL
    OR relationship IN ('BAN_THAN', 'VO_CHONG', 'CON', 'CHA_ME', 'ONG_BA', 'ANH_CHI_EM', 'KHAC')),
  CONSTRAINT ck_patients_gender CHECK (gender IS NULL OR gender IN ('NAM', 'NU', 'KHAC'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

CREATE TABLE appointments (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_code VARCHAR(30) NOT NULL,
  user_id BIGINT NULL,
  patient_id BIGINT NOT NULL,
  service_type VARCHAR(30) NOT NULL,
  slot_date DATE NOT NULL,
  slot_start VARCHAR(5) NOT NULL,
  expected_time DATETIME(3) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'BOOKED',
  source VARCHAR(10) NOT NULL,
  created_by BIGINT NOT NULL,
  rescheduled_from_id BIGINT NULL,
  cancel_reason VARCHAR(500) NULL,
  cancelled_by BIGINT NULL,
  cancelled_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_appointments_ticket (ticket_code),
  KEY ix_appointments_slot (slot_date, slot_start, status),
  KEY ix_appointments_patient (patient_id, slot_date),
  KEY ix_appointments_user (user_id, slot_date),
  KEY ix_appointments_created_by (created_by),
  KEY ix_appointments_rescheduled_from (rescheduled_from_id),
  KEY ix_appointments_cancelled_by (cancelled_by),
  CONSTRAINT fk_appointments_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_appointments_patient FOREIGN KEY (patient_id) REFERENCES patients (id),
  CONSTRAINT fk_appointments_created_by FOREIGN KEY (created_by) REFERENCES users (id),
  CONSTRAINT fk_appointments_rescheduled_from FOREIGN KEY (rescheduled_from_id) REFERENCES appointments (id),
  CONSTRAINT fk_appointments_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users (id),
  CONSTRAINT ck_appointments_service CHECK (service_type IN
    ('CHUA_RO', 'CHAM_CUU', 'VAT_LY_TRI_LIEU', 'XOA_BOP_BAM_HUYET', 'BOC_THUOC')),
  CONSTRAINT ck_appointments_status CHECK (status IN ('BOOKED', 'CHECKED_IN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
  CONSTRAINT ck_appointments_source CHECK (source IN ('ONLINE', 'COUNTER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

CREATE TABLE checkins (
  id BIGINT NOT NULL AUTO_INCREMENT,
  appointment_id BIGINT NOT NULL,
  patient_id BIGINT NOT NULL,
  visit_date DATE NOT NULL,
  check_in_time DATETIME(3) NOT NULL,
  priority_group VARCHAR(10) NOT NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'WAITING',
  seat_no TINYINT NULL,
  entered_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  -- Hai cột sinh tự động thay cho partial index của MongoDB: UNIQUE bỏ qua NULL.
  -- Mỗi phiếu có tối đa một check-in đang hiệu lực
  active_appointment_id BIGINT GENERATED ALWAYS AS
    (IF(status IN ('WAITING', 'IN_ROOM', 'COMPLETED'), appointment_id, NULL)) STORED,
  -- Một ghế chỉ có một người trong phòng khám tại một thời điểm
  in_room_seat_no TINYINT GENERATED ALWAYS AS (IF(status = 'IN_ROOM', seat_no, NULL)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uq_checkin_active (active_appointment_id),
  UNIQUE KEY uq_checkin_seat (visit_date, in_room_seat_no),
  KEY ix_checkins_queue (visit_date, status, check_in_time),
  KEY ix_checkins_appointment (appointment_id),
  KEY ix_checkins_patient (patient_id),
  CONSTRAINT fk_checkins_appointment FOREIGN KEY (appointment_id) REFERENCES appointments (id),
  CONSTRAINT fk_checkins_patient FOREIGN KEY (patient_id) REFERENCES patients (id),
  CONSTRAINT ck_checkins_priority CHECK (priority_group IN ('PRIORITY', 'NORMAL')),
  CONSTRAINT ck_checkins_status CHECK (status IN ('WAITING', 'IN_ROOM', 'COMPLETED', 'REVERTED')),
  CONSTRAINT ck_checkins_seat CHECK (seat_no IS NULL OR seat_no BETWEEN 1 AND 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

CREATE TABLE medical_records (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL,
  appointment_id BIGINT NOT NULL,
  checkin_id BIGINT NULL,
  visit_date DATE NOT NULL,
  diagnosis TEXT NULL,
  pulse VARCHAR(500) NULL,
  blood_pressure VARCHAR(500) NULL,
  -- treatmentNotes.acupuncture / acupressure / other
  treatment_acupuncture TEXT NULL,
  treatment_acupressure TEXT NULL,
  treatment_other TEXT NULL,
  medical_history TEXT NULL,
  follow_up_date DATE NULL,
  doctor_name VARCHAR(255) NULL,
  recorded_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_medical_records_appointment (appointment_id),
  KEY ix_medical_records_patient (patient_id, visit_date),
  KEY ix_medical_records_checkin (checkin_id),
  KEY ix_medical_records_recorded_by (recorded_by),
  CONSTRAINT fk_medical_records_patient FOREIGN KEY (patient_id) REFERENCES patients (id),
  CONSTRAINT fk_medical_records_appointment FOREIGN KEY (appointment_id) REFERENCES appointments (id),
  CONSTRAINT fk_medical_records_checkin FOREIGN KEY (checkin_id) REFERENCES checkins (id),
  CONSTRAINT fk_medical_records_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

-- editHistory của bệnh án: mỗi lần sửa một dòng, thứ tự theo id
CREATE TABLE medical_record_edits (
  id BIGINT NOT NULL AUTO_INCREMENT,
  medical_record_id BIGINT NOT NULL,
  edited_by BIGINT NULL,
  edited_by_name VARCHAR(255) NULL,
  edited_at DATETIME(3) NULL,
  changes JSON NULL,
  PRIMARY KEY (id),
  KEY ix_medical_record_edits_record (medical_record_id),
  KEY ix_medical_record_edits_user (edited_by),
  CONSTRAINT fk_medical_record_edits_record FOREIGN KEY (medical_record_id) REFERENCES medical_records (id) ON DELETE CASCADE,
  CONSTRAINT fk_medical_record_edits_user FOREIGN KEY (edited_by) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

CREATE TABLE posts (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(255) NOT NULL,
  slug VARCHAR(255) NULL,
  category VARCHAR(20) NOT NULL,
  summary VARCHAR(1000) NOT NULL DEFAULT '',
  thumbnail_url VARCHAR(500) NULL,
  author_id BIGINT NOT NULL,
  author_name VARCHAR(255) NOT NULL,
  content MEDIUMTEXT NOT NULL DEFAULT (''),
  -- HIDDEN: đã công khai rồi tạm ẩn khỏi website, giữ nguyên ngày đăng
  status VARCHAR(10) NOT NULL DEFAULT 'DRAFT',
  published_at DATETIME(3) NULL,
  -- Chỉ nhận 1 hoặc NULL; UNIQUE bỏ qua NULL nên mỗi lúc chỉ một bài được ghim. Bỏ ghim thì đặt NULL.
  pinned TINYINT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_posts_slug (slug),
  UNIQUE KEY uq_posts_pinned (pinned),
  KEY ix_posts_listing (status, category, published_at),
  KEY ix_posts_author (author_id),
  CONSTRAINT fk_posts_author FOREIGN KEY (author_id) REFERENCES users (id),
  CONSTRAINT ck_posts_category CHECK (category IN ('TIN_TUC', 'HUONG_DAN', 'THIEN_NGUYEN', 'SU_KIEN', 'TRANG')),
  CONSTRAINT ck_posts_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'HIDDEN')),
  CONSTRAINT ck_posts_pinned CHECK (pinned IS NULL OR pinned = 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

-- Ảnh Admin tải lên, lưu thẳng trong cơ sở dữ liệu (tối đa 3 MB mỗi ảnh)
CREATE TABLE uploads (
  id BIGINT NOT NULL AUTO_INCREMENT,
  content_type VARCHAR(100) NOT NULL,
  size INT NOT NULL,
  data MEDIUMBLOB NOT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_uploads_created_by (created_by),
  CONSTRAINT fk_uploads_created_by FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

-- Thay chỉ mục TTL của MongoDB: tác vụ định kỳ xoá bản ghi quá hạn 1 giờ (xem ix_verification_codes_expires)
CREATE TABLE verification_codes (
  id BIGINT NOT NULL AUTO_INCREMENT,
  phone VARCHAR(20) NOT NULL,
  -- Địa chỉ nhận mã khi gửi qua email (NULL: gửi tin nhắn tới số điện thoại); dùng để đếm giới hạn mỗi email
  email VARCHAR(255) NULL,
  code_hash VARCHAR(64) NOT NULL,
  purpose VARCHAR(20) NOT NULL,
  register_data JSON NULL,
  attempts INT NOT NULL DEFAULT 0,
  expires_at DATETIME(3) NOT NULL,
  used_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_verification_codes_phone (phone, created_at),
  KEY ix_verification_codes_email (email, created_at),
  KEY ix_verification_codes_expires (expires_at),
  CONSTRAINT ck_verification_codes_purpose CHECK (purpose IN ('REGISTER', 'NEW_DEVICE', 'RESET_PASSWORD', 'CHANGE_EMAIL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

CREATE TABLE trusted_devices (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  device_hash VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  last_used_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_trusted_devices_user_hash (user_id, device_hash),
  CONSTRAINT fk_trusted_devices_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

-- Bộ đếm sinh mã bệnh nhân và mã phiếu khám, khoá chuỗi như "ticket-2026-11-02"
CREATE TABLE counters (
  name VARCHAR(100) NOT NULL,
  seq BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

-- Số chỗ đã giữ của từng khung giờ; giữ chỗ bằng UPDATE ... WHERE count < 6 nên không thể vượt sức chứa
CREATE TABLE slot_counters (
  id BIGINT NOT NULL AUTO_INCREMENT,
  slot_date DATE NOT NULL,
  start_time VARCHAR(5) NOT NULL,
  `count` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uq_slot_counters_slot (slot_date, start_time),
  CONSTRAINT ck_slot_counters_count CHECK (`count` >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;

-- Cài đặt vận hành (ví dụ: tự động mời người kế tiếp vào ghế trống)
CREATE TABLE settings (
  name VARCHAR(100) NOT NULL,
  `value` JSON NULL,
  PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_ci;
