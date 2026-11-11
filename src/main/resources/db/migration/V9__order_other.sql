-- Chỉ định "Khác": bác sĩ ghi rõ là kỹ thuật gì.
ALTER TABLE checkins ADD COLUMN treatment_other TEXT NULL;
