# Phòng khám Chúc Từ – Backend

API cho website đặt lịch và tiếp nhận bệnh nhân của **Phòng chẩn trị Y học cổ truyền từ thiện Chúc Từ**.
Giao diện người dùng nằm ở thư mục [`../frontend`](../frontend).

| | |
| --- | --- |
| **Ngôn ngữ** | Java 21 |
| **Framework** | Spring Boot 4, Spring Security, Spring Data JPA |
| **Cơ sở dữ liệu** | MySQL 8.0 trở lên, tạo bảng bằng Flyway |
| **Công cụ build** | Maven (dùng `./mvnw` có sẵn, không cần cài Maven) |
| **Địa chỉ API** | `http://localhost:4000/api` |

---

## Mục lục

1. [Chạy thử trên máy](#1-chạy-thử-trên-máy)
2. [Tài khoản mẫu](#2-tài-khoản-mẫu)
3. [Chế độ demo](#3-chế-độ-demo)
4. [Cấu hình `.env`](#4-cấu-hình-env)
5. [Gửi mã xác nhận: SMS và email](#5-gửi-mã-xác-nhận-sms-và-email)
6. [Kiểm thử](#6-kiểm-thử)
7. [Chạy bằng Docker](#7-chạy-bằng-docker)
8. [Đưa lên mạng (triển khai)](#8-đưa-lên-mạng-triển-khai)
9. [Cấu trúc mã nguồn](#9-cấu-trúc-mã-nguồn)
10. [Quy tắc nghiệp vụ chính](#10-quy-tắc-nghiệp-vụ-chính)
11. [Gặp lỗi thường gặp](#11-gặp-lỗi-thường-gặp)

---

## 1. Chạy thử trên máy

### Cần cài sẵn

- **JDK 21**, ví dụ Eclipse Temurin.
- **MySQL 8.0 trở lên**. Bản 8.4 đã được thử.

### Bước 1: Tạo database (chỉ làm một lần)

Mở `scripts/setup-db.sql`, thay `<mật khẩu tự chọn>` bằng mật khẩu của bạn, rồi chạy bằng tài khoản root:

```bash
mysql -u root -p < scripts/setup-db.sql
```

Script tạo:

- người dùng MySQL `chuctu`
- database `chuctu`, dùng khi chạy
- database `chuctu_test`, dùng khi kiểm thử
- quyền trên `chuctu_demo`, dùng cho chế độ demo (tự tạo khi chạy)

> Đừng commit file này sau khi đã điền mật khẩu thật.

### Bước 2: Tạo file `.env`

```bash
cp .env.example .env
```

Mở `.env` và điền `DB_PASSWORD` bằng mật khẩu vừa đặt. Các biến khác để trống cũng chạy được (xem [mục 4](#4-cấu-hình-env)).

### Bước 3: Chạy server

**Git Bash** (terminal mặc định trong VS Code trên máy này):

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot"
./mvnw spring-boot:run
```

**PowerShell**:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot"
.\mvnw spring-boot:run
```

Đổi đường dẫn `JAVA_HOME` nếu JDK của bạn nằm ở chỗ khác.

Khi thấy dòng `Started ClinicApplication` là server đã chạy. Kiểm tra bằng cách mở http://localhost:4000/api/health, nếu thấy `{"ok":true}` là được.

Lần chạy đầu, server tự tạo bảng và tạo sẵn tài khoản Admin, Tiếp tân cùng 4 trang thông tin (Giới thiệu, Dịch vụ, Hướng dẫn, Liên hệ).

### Bước 4: Chạy giao diện

Mở một terminal khác, vào `../frontend` và chạy `npm run dev`, rồi mở http://localhost:5173.

---

## 2. Tài khoản mẫu

Chỉ có khi chạy ở máy (không phải production). Đổi được bằng các biến `SEED_*` trong `.env`.

| Vai trò | Đăng nhập ở | Tài khoản | Mật khẩu |
| --- | --- | --- | --- |
| Admin | `/noi-bo/dang-nhap` | `admin@chuctu.vn` hoặc `0900000001` | `ChucTu@2026` |
| Tiếp tân | `/noi-bo/dang-nhap` | `letan@chuctu.vn` hoặc `0900000002` | `ChucTu@2026` |
| Người dùng | `/dang-nhap` | Số `0912345678` (chỉ có ở chế độ demo) hoặc tự đăng ký | Không dùng mật khẩu |

Người dùng đăng nhập bằng số điện thoại và mã 6 số. Khi `SMS_PROVIDER=console`, mã hiện ngay trên màn hình nhập mã và trong log server.

---

## 3. Chế độ demo

Muốn có sẵn dữ liệu để thử (bệnh nhân, lịch hẹn, hàng đợi, bài viết):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

- Dùng database riêng `chuctu_demo`. **Mỗi lần khởi động, database này bị xoá và tạo lại.**
- Database `chuctu` không bị đụng tới.
- Bị từ chối khi `NODE_ENV=production`.

Chỉ nạp tài khoản và trang thông tin vào `chuctu` rồi thoát, không chạy server:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--seed
```

---

## 4. Cấu hình `.env`

Chép từ `.env.example`. File `.env` đã nằm trong `.gitignore`, **không bao giờ commit hay gửi file này cho người khác**.
Biến để trống thì dùng giá trị mặc định. Biến môi trường của hệ điều hành được ưu tiên hơn `.env`.

### Cơ bản

| Biến | Ý nghĩa | Mặc định |
| --- | --- | --- |
| `PORT` | Cổng của API | `4000` |
| `NODE_ENV` | `development` hoặc `production`. Production bật các kiểm tra nghiêm hơn và cookie `Secure`. | `development` |
| `DB_URL` | Địa chỉ MySQL, dạng `jdbc:mysql://localhost:3306/chuctu` | |
| `DB_USERNAME`, `DB_PASSWORD` | Tài khoản MySQL | |
| `JWT_SECRET` | Khoá ký phiên đăng nhập. **Production: chuỗi ngẫu nhiên, ít nhất 32 ký tự.** | khoá mẫu |
| `CLIENT_ORIGIN` | Địa chỉ của giao diện, nhiều địa chỉ thì ngăn bằng dấu phẩy. Xem [mục 8](#8-đưa-lên-mạng-triển-khai). | `http://localhost:5173` |

### Tài khoản tạo sẵn

| Biến | Ý nghĩa |
| --- | --- |
| `SEED_ADMIN_PHONE`, `SEED_ADMIN_EMAIL`, `SEED_ADMIN_NAME`, `SEED_ADMIN_PASSWORD` | Tài khoản Admin |
| `SEED_RECEPTIONIST_PHONE`, `SEED_RECEPTIONIST_EMAIL`, `SEED_RECEPTIONIST_NAME`, `SEED_RECEPTIONIST_PASSWORD` | Tài khoản Tiếp tân |

- Chỉ tạo khi chưa có tài khoản với số điện thoại đó. Đổi mật khẩu ở đây **không** đổi mật khẩu của tài khoản đã có.
- Production **bắt buộc** có `SEED_ADMIN_PASSWORD` (ít nhất 8 ký tự, khác mật khẩu mẫu). Không đặt `SEED_RECEPTIONIST_PASSWORD` thì không tạo Tiếp tân, Admin tự thêm sau.

### Gửi mã xác nhận

| Biến | Ý nghĩa |
| --- | --- |
| `SMS_PROVIDER` | `console` (in mã ra log) hoặc `firebase` |
| `FIREBASE_PROJECT_ID`, `FIREBASE_API_KEY`, `FIREBASE_AUTH_DOMAIN`, `FIREBASE_APP_ID` | Cấu hình Web app Firebase |
| `FIREBASE_AUTH_EMULATOR_HOST` | Ví dụ `127.0.0.1:9099` để thử bằng Firebase Emulator |
| `FIREBASE_TEST_NUMBERS_ONLY` | `1`: chỉ dùng số thử, bỏ qua reCAPTCHA. Không có tác dụng ở production. |
| `EMAIL_PROVIDER` | `console` (in mã ra log) hoặc `smtp` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`, `MAIL_FROM` | Bắt buộc khi `EMAIL_PROVIDER=smtp` |
| `EMAIL_DEV_CODE` | Mã cố định cho email khi phát triển, mặc định `123456` |

---

## 5. Gửi mã xác nhận: SMS và email

| Ai | Dùng mã để | Gửi qua |
| --- | --- | --- |
| Người dùng | Đăng ký, đăng nhập | SMS (`SMS_PROVIDER`) |
| Nhân viên | Quên mật khẩu, đổi email | Email (`EMAIL_PROVIDER`) |

### SMS bằng Firebase

1. Trên [Firebase Console](https://console.firebase.google.com), tạo project và thêm **Web app**. Chép `apiKey`, `authDomain`, `projectId`, `appId` vào `.env`.
2. **Authentication → Sign-in method**: bật **Phone**.
3. **Authentication → Settings → Authorized domains**: thêm `localhost` và tên miền thật của trang web.
4. **SMS region policy**: cho phép Việt Nam.
5. Đặt `SMS_PROVIDER=firebase`.

Trình duyệt tự gửi và xác nhận mã với Firebase. Server chỉ kiểm tra token Firebase trả về (chỉ cần Project ID, không cần khoá bí mật). Giới hạn 3 mã mỗi giờ vẫn do server giữ.

**Thử không tốn tiền:** vào *Phone numbers for testing*, thêm số thử kèm mã cố định, rồi đặt `FIREBASE_TEST_NUMBERS_ONLY=1`.

### Email bằng Gmail

1. Bật xác minh 2 bước cho tài khoản Gmail, rồi tạo **Mật khẩu ứng dụng** tại https://myaccount.google.com/apppasswords.
2. Điền vào `.env`:

   ```
   EMAIL_PROVIDER=smtp
   SMTP_HOST=smtp.gmail.com
   SMTP_PORT=587
   SMTP_USER=dia-chi@gmail.com
   SMTP_PASS=mật khẩu ứng dụng 16 ký tự
   MAIL_FROM=Phòng khám Chúc Từ <dia-chi@gmail.com>
   ```

> **Lưu ý:** `MAIL_FROM` phải chứa địa chỉ email: chỉ địa chỉ, hoặc dạng `Tên <địa chỉ>`. Nếu chỉ ghi tên, email sẽ không gửi được. Địa chỉ nên trùng `SMTP_USER`.

Cả Firebase và Gmail đều đã được thử thật (ngày 03/11/2026).

---

## 6. Kiểm thử

```bash
./mvnw test      # chạy toàn bộ kiểm thử
./mvnw verify    # kiểm thử rồi đóng gói thành file .jar
```

- Kiểm thử chạy trên **MySQL thật**, trong database `chuctu_test`. Mỗi test xoá sạch và nạp lại dữ liệu của `chuctu_test`, không đụng `chuctu`.
- Tên database kiểm thử được lấy từ `DB_URL` (đổi tên database thành `chuctu_test`), nên `DB_URL` phải trỏ tới `chuctu`.
- Kiểm thử **không** đọc cấu hình SMS, email, Firebase, tài khoản mẫu từ `.env`, và không bao giờ gửi SMS hay email thật.
- Chỉ chạy một lần kiểm thử tại một thời điểm, vì mọi lần chạy dùng chung `chuctu_test`.

---

## 7. Chạy bằng Docker

Docker tự chạy cả MySQL 8.4 và server, không cần cài MySQL.

```bash
docker compose up --build
```

- Trong `.env` phải có `DB_PASSWORD`, `JWT_SECRET` (ít nhất 32 ký tự) và `SEED_ADMIN_PASSWORD`. Thiếu biến nào thì lệnh dừng ngay và báo thiếu.
- Docker chạy ở chế độ production nên không có tài khoản mẫu, và mã 6 số chỉ xem được trong log: `docker compose logs -f server`.
- Đổi cổng trên máy bằng `HOST_PORT`, ví dụ `HOST_PORT=4300`.
- Xoá cả dữ liệu: `docker compose down -v`.

---

## 8. Đưa lên mạng (triển khai)

Cần hai thứ: **một MySQL 8** trên mạng và **một nơi chạy server**. Server có thể chạy bằng `Dockerfile` trong thư mục này, ví dụ trên Render hay Railway.

Biến môi trường cần đặt trên server:

- `NODE_ENV=production`
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `JWT_SECRET`: chuỗi ngẫu nhiên, ít nhất 32 ký tự
- `SEED_ADMIN_PASSWORD`
- `CLIENT_ORIGIN`: **địa chỉ của trang web**, ví dụ `https://chuctu.vercel.app`
- Cấu hình SMS và email như ở [mục 5](#5-gửi-mã-xác-nhận-sms-và-email)

> **Quan trọng về `CLIENT_ORIGIN`:** để chống tấn công giả mạo yêu cầu (CSRF), server từ chối (lỗi 403) mọi thao tác thay đổi dữ liệu gửi từ trang web khác. Trang được chấp nhận nếu địa chỉ nằm trong `CLIENT_ORIGIN` hoặc có cùng tên miền với server. Nếu quên đặt `CLIENT_ORIGIN`, người dùng sẽ không đăng nhập hay đặt lịch được.

Nhớ thêm tên miền của trang web vào **Authorized domains** trên Firebase.

---

## 9. Cấu trúc mã nguồn

```
src/main/java/vn/chuctu/clinic/
  auth/          đăng ký, đăng nhập, mã xác nhận, Firebase, gửi email
  account/       tài khoản người dùng và nhân viên (Admin quản lý)
  patient/       hồ sơ bệnh nhân
  appointment/   đặt lịch, huỷ, đổi giờ, khung giờ
  queue/         check-in, hàng đợi 5 ghế, mời vào khám
  record/        phiếu kết quả khám
  post/          bài viết, trang thông tin, ảnh tải lên
  dashboard/     số liệu trang Tổng quan
  security/      phiên đăng nhập, CORS, chống CSRF, giới hạn kích thước yêu cầu
  seed/          tạo tài khoản mẫu và dữ liệu demo
  common/        lỗi, giờ Việt Nam, hằng số, giao dịch dùng chung

src/main/resources/
  application.yml          cấu hình Spring
  db/migration/            Flyway: các file tạo và sửa bảng

src/test/                  kiểm thử, chạy trên MySQL thật
scripts/setup-db.sql       tạo người dùng và database MySQL
docs/                      tài liệu (xem bên dưới)
```

**Tài liệu trong `docs/`:**

- `thiet-ke/`: thiết kế và kế hoạch của lần chuyển từ Node.js + MongoDB sang Spring Boot + MySQL.
- `ket-qua-doi-chieu.md`: kết quả đối chiếu với bản Node cũ, kết quả thử tay, và những điểm cố ý xử lý khác bản cũ.

---

## 10. Quy tắc nghiệp vụ chính

Mọi quy tắc đều được kiểm tra ở server. Giao diện ẩn hay hiện nút chỉ để người dùng dễ thao tác.

**Mã xác nhận**
- Lưu dạng băm, hết hạn sau 5 phút.
- Tối đa 3 mã mỗi giờ cho một số điện thoại, phải chờ 60 giây mới gửi lại.
- Nhập sai tối đa 5 lần, kể cả khi gửi nhiều yêu cầu cùng lúc.

**Đặt lịch**
- Đặt trước tối đa 30 ngày, chỉ chọn được các khung chưa bắt đầu.
- Khung giờ: 8:00, 9:00, 10:00, 11:00, 13:00, 14:00, 15:00, 16:00.
- Mỗi khung tối đa 6 phiếu, không vượt kể cả khi nhiều người đặt cùng lúc.
- Một bệnh nhân chỉ có một phiếu còn hiệu lực mỗi ngày, kể cả khi đổi giờ.

**Huỷ phiếu**
- Người dùng tự huỷ được khi chưa check-in.
- Đã được tiếp nhận thì phải báo tiếp tân để huỷ.

**Hàng đợi**
- Nhận bệnh từ 8:00 đến 16:30. Phiếu không đến tự huỷ lúc 17:00.
- Phòng khám có 5 ghế. Nhóm Ưu tiên (đến đúng khung giờ đã đặt) đứng trước nhóm Thường, trong mỗi nhóm xếp theo giờ check-in.
- Có thể bật tự động mời người kế tiếp vào ghế trống.

**Phiếu kết quả**
- Sửa được trong 24 giờ sau khi xác nhận.

**Tài khoản**
- Khoá tài khoản hay đổi vai trò có hiệu lực ngay.
- Nhân viên dùng mật khẩu tạm phải đổi mật khẩu trước khi làm việc.

**Mã số**
- Mã bệnh nhân dạng `CT-BN-26-0001`, mã phiếu khám dạng `CT-PK-26-2010-001`.
- Giờ nghiệp vụ tính theo giờ Việt Nam.

---

## 11. Gặp lỗi thường gặp

| Hiện tượng | Cách xử lý |
| --- | --- |
| `$env:JAVA_HOME: command not found`, `.\mvnw: command not found` | Terminal đang là Git Bash. Dùng lệnh Git Bash ở [bước 3](#bước-3-chạy-server). |
| `Port 4000 was already in use` | Đã có server khác ở cổng 4000. Tắt nó, hoặc đặt `PORT` khác trong `.env`. |
| `Access denied for user 'chuctu'` | `DB_PASSWORD` trong `.env` chưa trùng mật khẩu đặt ở `setup-db.sql`. |
| Quên mật khẩu báo "Chưa gửi được email" | Kiểm tra `MAIL_FROM` có địa chỉ email, Mật khẩu ứng dụng Gmail còn hiệu lực, và cổng 587 không bị chặn. |
| Đăng nhập, đặt lịch báo lỗi 403 trên trang thật | `CLIENT_ORIGIN` chưa có địa chỉ của trang web. |
