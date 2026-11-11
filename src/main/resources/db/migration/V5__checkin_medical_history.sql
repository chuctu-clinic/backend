-- Bác sĩ ghi tiền sử bệnh ngay lúc khám sơ bộ; phiếu kết quả lấy lại từ đây.
ALTER TABLE checkins ADD COLUMN medical_history TEXT NULL;
