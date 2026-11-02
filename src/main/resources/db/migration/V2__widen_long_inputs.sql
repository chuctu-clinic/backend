-- Node không giới hạn độ dài các ô này, nên giá trị hợp lệ dài không được làm INSERT/UPDATE lỗi (500).
-- Đường dẫn ảnh bìa: URL đầy đủ có thể dài vài nghìn ký tự.
ALTER TABLE posts MODIFY thumbnail_url TEXT NULL;

-- Email tài khoản và email nhận mã: zod .email() không có giới hạn độ dài. Hai cột này nằm trong chỉ mục nên không dùng TEXT;
-- 700 ký tự (2800 byte utf8mb4) là mức rộng nhất còn vừa giới hạn 3072 byte của khoá chỉ mục (email, created_at).
ALTER TABLE users MODIFY email VARCHAR(700) NULL;
ALTER TABLE verification_codes MODIFY email VARCHAR(700) NULL;
