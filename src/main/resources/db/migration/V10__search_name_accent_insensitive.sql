-- Tìm theo họ tên không phân biệt dấu (A10): nhân viên thường gõ không dấu ("nguyen", "van", "duc").
-- utf8mb4_0900_ai_ci: không phân biệt hoa thường, không phân biệt dấu, "đ" khớp "d". Chỉ đổi cột họ tên;
-- số điện thoại, email, mã phiếu, mã bệnh nhân và tiêu đề bài viết giữ collation cũ.
ALTER TABLE users MODIFY full_name VARCHAR(255) NOT NULL COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE patients MODIFY full_name VARCHAR(255) NOT NULL COLLATE utf8mb4_0900_ai_ci;
