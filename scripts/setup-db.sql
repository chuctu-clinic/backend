-- Chạy một lần bằng tài khoản root: mysql -u root -p < scripts/setup-db.sql
-- Thay <mật khẩu tự chọn> trước khi chạy, rồi điền cùng mật khẩu đó vào backend/.env (DB_PASSWORD).
-- Không đưa mật khẩu thật vào file này.
CREATE USER 'chuctu'@'localhost' IDENTIFIED BY '<mật khẩu tự chọn>';
CREATE DATABASE chuctu CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;
CREATE DATABASE chuctu_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;
GRANT ALL ON chuctu.* TO 'chuctu'@'localhost';
GRANT ALL ON chuctu_test.* TO 'chuctu'@'localhost';
GRANT ALL ON chuctu_demo.* TO 'chuctu'@'localhost';
