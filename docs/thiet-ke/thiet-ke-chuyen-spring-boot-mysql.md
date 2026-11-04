# Chuyển backend sang Spring Boot và MySQL

Ngày: 2026-11-02. Trạng thái: đã duyệt ngày 2026-11-02.

## 1. Mục đích

Sếp yêu cầu đổi backend của website Phòng khám Chúc Từ từ Node.js/Express + MongoDB sang Java Spring Boot + MySQL. Đây là thay đổi công nghệ, không phải thay đổi sản phẩm: người dùng, tiếp tân và Admin phải thấy website chạy y như cũ.

## 2. Mục tiêu, ngoài phạm vi, điều không được xảy ra

**Mục tiêu**

- Toàn bộ 77 endpoint dưới `/api` chạy trên Spring Boot, cùng đường dẫn, phương thức, mã HTTP và dạng JSON.
- Dữ liệu lưu trong MySQL. Cấu trúc bảng do Flyway quản lý.
- Mọi quy tắc nghiệp vụ (BR) và thông báo tiếng Việt giữ nguyên.
- 196 test hiện có được chuyển sang Java, cùng ý nghĩa kiểm tra.
- Frontend chạy với backend mới, chỉ sửa khi bắt buộc.

**Ngoài phạm vi**

- Tính năng mới, đổi giao diện, đổi quy tắc nghiệp vụ.
- Chuyển dữ liệu từ MongoDB. Dữ liệu trên Atlas chỉ là dữ liệu thử (người dùng xác nhận), MySQL bắt đầu trống và nạp bằng seed.
- Chuyển ảnh ra kho lưu trữ riêng.
- Chọn nơi triển khai, dựng CI/CD.
- Sửa các việc còn tồn trong README (giới hạn số lần đăng nhập nội bộ, nhà cung cấp SMS trong nước).
- Giữ cơ chế chuyển cookie cũ `ct_staff` (chỉ phục vụ phiên của bản trước, không còn ý nghĩa khi mọi phiên đều mất hiệu lực).

**Điều không được xảy ra**

- Một khung giờ có hơn 6 phiếu, hoặc một ghế có hai người, khi nhiều người thao tác cùng lúc.
- Ngày khám hoặc ngày sinh lệch một ngày.
- Hồ sơ bệnh án hoặc phiếu khám của người này lộ sang người khác do sai kiểm tra quyền.
- Bỏ code Node.js trước khi bản Java vượt qua toàn bộ test và thử tay.

## 3. Hợp đồng với frontend

Code Node hiện tại là nguồn sự thật. Khi tài liệu này không nói rõ, bản Java làm đúng như `backend/src`. Frontend chỉ dùng trường `id`, không phụ thuộc `_id`.

Các điểm phải giữ chính xác:

| Điểm | Yêu cầu |
|---|---|
| Lỗi | `{"error":{"code","message",...details}}`, kể cả `fields` và `retryAfter`. Lỗi kiểm tra dữ liệu có mã `VALIDATION`, `fields` theo từng ô, khoá lồng dùng dấu chấm (`treatmentNotes.other`). |
| ID | Luôn là chuỗi trong JSON (`"12"`), kể cả `userId`, `patientId`, `rescheduledFromId`. |
| Thời điểm | ISO UTC đúng 3 chữ số mili giây, ví dụ `2026-11-02T03:00:00.000Z`, giống `Date.toJSON()`. |
| Ngày không có giờ | Mỗi trường giữ đúng dạng Node đang trả. `dob` trả nguyên `Date` nên có dạng `YYYY-MM-DDT00:00:00.000Z`. `slotDate`, `visitDate` và `followUpDate` của bệnh án, `visitDate` của check-in trả `YYYY-MM-DD` (frontend tách chuỗi này theo dấu `-`). Đối chiếu từng response với code Node. |
| Trường rỗng | Trường Node trả `null` thì Java trả `null`. Trường Node bỏ hẳn (`undefined`, ví dụ `phone` trong phiếu của người dùng) thì Java cũng bỏ. |
| Gửi lên ba trạng thái | Không gửi = giữ nguyên, `null` hoặc `""` = xoá. Không map vào POJO thường; dùng kiểu phân biệt được "vắng mặt". |
| Cookie | `ct_session` (JWT, 30 ngày, không có `maxAge` nếu không chọn "giữ đăng nhập"), `ct_device` (1 năm), `httpOnly`, `path=/`. Production: `SameSite=None; Secure`. Phát triển: `Lax`. |
| Header | `x-session-user`: lệch với phiên thì trả 409 `SESSION_CHANGED`, trừ `/api/auth/*`. |
| Ảnh | `POST /api/admin/uploads` nhận thẳng byte ảnh, tối đa 3 MB. `GET /api/uploads/:id` có `Cache-Control: public, max-age=31536000, immutable`. |
| Giới hạn JSON | 1 MB. JSON hỏng trả 400 `BAD_JSON`. |
| Trùng khoá | 409 `DUPLICATE`, tương đương mã 11000 của MongoDB. |
| ID sai dạng | Trong URL trả 404 như `CastError` hiện nay. Trong body trả lỗi `VALIDATION` "Mã không hợp lệ." |
| CORS, bảo mật | Giữ `CLIENT_ORIGIN` nhiều địa chỉ, `credentials`, các header tương đương helmet (không CSP), tin một lớp proxy. |

ID đổi sang số nên phải sửa các chỗ đang kiểm tra 24 ký tự hex: `objectId`, `getImage`, `thumbnailUrl` (`/api/uploads/<số>`), `getPublished`. Ở `getPublished`, chuỗi toàn chữ số là ID, còn lại là slug. Mã nguồn frontend không có chỗ nào kiểm tra định dạng 24 ký tự.

## 4. Thiết kế

### Công nghệ và cấu trúc

- Java 21, Spring Boot bản ổn định mới nhất khi tạo project, Maven kèm Maven Wrapper.
- Spring Web, Spring Security (chỉ cho filter phiên và phân quyền, tắt form login), Spring Data JPA, Flyway, Spring Mail. Kiểm tra dữ liệu gửi lên bằng lớp đọc JSON viết riêng thay vì Bean Validation, để trả lỗi sai kiểu theo từng ô và phân biệt "không gửi" với `null` giống zod.
- Thư mục `D:\chuctu-clinic\backend-java`, là git repo riêng như `backend` và `frontend`. `backend/` giữ nguyên cho tới khi hoàn tất.
- Chạy ở cổng 4000 để frontend không phải đổi cấu hình proxy.
- Đọc cấu hình từ biến môi trường và file `.env` cạnh project, giữ nguyên tên biến. `MONGODB_URI` thay bằng `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
- Thiếu cấu hình khi production thì không khởi động, giống `config.js`: `JWT_SECRET` phải ít nhất 32 ký tự và khác giá trị mẫu, SMTP thiếu thì báo lỗi.

### Cơ sở dữ liệu

MySQL 8.4 LTS, InnoDB, `utf8mb4`, collation mặc định `utf8mb4_0900_as_ci`. Collation này không phân biệt hoa thường nhưng phân biệt dấu, khớp với tìm kiếm hiện tại: "Nguyễn" không trùng "Nguyen". Tìm kiếm chứa chuỗi dùng `LIKE` có escape `%` và `_`.

| Collection | Bảng | Ghi chú |
|---|---|---|
| users | `users` | `email` nullable, `UNIQUE` (MySQL cho nhiều NULL). |
| patients | `patients` | |
| appointments | `appointments` | `slot_date DATE`. |
| checkins | `checkins` | Hai cột sinh tự động cho ràng buộc có điều kiện, xem dưới. |
| medicalRecords | `medical_records` + `medical_record_edits` | `treatmentNotes` thành ba cột. `changes` lưu cột `JSON`. |
| posts | `posts` | `content MEDIUMTEXT`, `slug` nullable `UNIQUE`. |
| uploads | `uploads` | `data MEDIUMBLOB`. |
| verificationCodes | `verification_codes` | `register_data JSON`. |
| trustedDevices | `trusted_devices` | `UNIQUE (user_id, device_hash)`. |
| counters | `counters` | Khoá chuỗi như `ticket-2026-11-02`. |
| slotCounters | `slot_counters` | `UNIQUE (slot_date, start_time)`. |
| settings | `settings` | `value JSON`. |

- ID là `BIGINT AUTO_INCREMENT`. Khoá ngoại khai báo thật.
- Thời điểm lưu `DATETIME(3)` theo UTC, Java dùng `Instant`, Hibernate đặt `jdbc.time_zone=UTC`, mọi `Instant` cắt về mili giây trước khi lưu. Nếu không, so sánh `expectedUpdatedAt` (`assertFresh`) sẽ sai.
- Ngày sinh và ngày khám dùng `DATE` và `LocalDate`, bỏ mẹo "nửa đêm UTC" trong `time.js`. Mọi phép tính theo giờ Việt Nam dùng `ZoneId.of("Asia/Ho_Chi_Minh")`.
- Thời gian lấy qua bean `java.time.Clock` để test cố định được giờ, thay cho `clock.now`.

Giữ các chỉ mục tương đương với Mongoose. Chỉ mục TTL của `verificationCodes` thay bằng tác vụ định kỳ xoá bản ghi quá hạn 1 giờ.

### Ràng buộc có điều kiện

MySQL không có partial index, nên dùng cột sinh tự động, tận dụng việc `UNIQUE` bỏ qua NULL:

```sql
-- checkins
active_appointment_id BIGINT GENERATED ALWAYS AS
  (IF(status IN ('WAITING','IN_ROOM','COMPLETED'), appointment_id, NULL)) STORED,
in_room_seat_no TINYINT GENERATED ALWAYS AS
  (IF(status = 'IN_ROOM', seat_no, NULL)) STORED,
UNIQUE KEY uq_checkin_active (active_appointment_id),
UNIQUE KEY uq_checkin_seat (visit_date, in_room_seat_no)
```

Một bài được ghim: cột `pinned TINYINT NULL` chỉ nhận `1` hoặc `NULL`, `UNIQUE (pinned)`, đúng cách làm hiện tại ("chỉ có giá trị true"). Bỏ ghim không làm đổi `updated_at`.

### Các thao tác đồng thời

Chuyển sang câu SQL nguyên tử, viết thủ công (`@Query` hoặc `JdbcTemplate`):

```sql
-- Giữ chỗ: không được tăng quá 6. Không chỉ dùng ON DUPLICATE KEY UPDATE, vì Connector/J mặc định
-- trả số dòng khớp, không phân biệt được "đã tăng" và "đã đủ chỗ".
INSERT INTO slot_counters (slot_date, start_time, count) VALUES (?, ?, 0)
  ON DUPLICATE KEY UPDATE count = count;
UPDATE slot_counters SET count = count + 1 WHERE slot_date = ? AND start_time = ? AND count < 6;
-- 0 dòng: lỗi 409 SLOT_FULL

-- Nhả chỗ
UPDATE slot_counters SET count = count - 1 WHERE slot_date = ? AND start_time = ? AND count > 0;

-- Sinh số thứ tự
INSERT INTO counters (name, seq) VALUES (?, LAST_INSERT_ID(1))
  ON DUPLICATE KEY UPDATE seq = LAST_INSERT_ID(seq + 1);
SELECT LAST_INSERT_ID();
```

- Các lệnh đổi trạng thái có điều kiện (`findOneAndUpdate({status:'BOOKED'})`) thành `UPDATE ... WHERE id = ? AND status = ?`, kiểm tra số dòng. 0 dòng thì trả lỗi `STALE` hoặc mã tương ứng như Node.
- Thao tác nhiều bước trong Node đang tự bù trừ khi lỗi (đặt lịch, đổi giờ, check-in). Bản Java dùng `@Transactional` thay cho code bù trừ, kết quả nhìn từ ngoài phải như cũ.
- Mời vào ghế: mỗi lần thử ghế chạy trong transaction riêng hoặc bằng JDBC, vì lỗi trùng khoá làm hỏng session Hibernate. Trùng ghế thì thử ghế kế tiếp, tối đa 5 lần như `seatOne`.
- Đặt lịch thử lại khi gặp deadlock MySQL (lỗi 1213), tối đa 3 lần, để người dùng không nhận lỗi 500.
- Kiểm tra "đã có phiếu trong ngày" (`ALREADY_BOOKED`) hiện không được bảo vệ khi đặt đồng thời. Giữ nguyên hành vi đó.

### Đăng nhập và bảo mật

- `OncePerRequestFilter` tương đương `loadUser`: đọc `ct_session`, kiểm JWT, luôn tải lại tài khoản từ database. JWT hợp lệ nhưng `sub` không phải số (phiên của bản cũ) được xử lý như phiên sai: xoá cookie, không trả 500.
- Quyền `requireUser`, `requireStaff`, `requireAdmin` và chặn tài khoản nội bộ `mustChangePassword` giữ đúng logic ở `middleware/auth.js`.
- JWT HS256 với `JWT_SECRET`. Mặc định khi phát triển đổi thành chuỗi dài ít nhất 32 byte. Khi chuyển, mọi người phải đăng nhập lại một lần, chấp nhận được vì không có dữ liệu thật.
- `hmac` cho OTP, thiết bị, `resetKey`, `passwordStamp` giữ đúng công thức HMAC-SHA256 hex. So sánh mã bằng `MessageDigest.isEqual`.
- Mật khẩu dùng `BCryptPasswordEncoder` độ mạnh 10. Mã OTP và ID thiết bị dùng `SecureRandom`.
- Token đặt lại mật khẩu giữ thời hạn 10 phút và khoá riêng.

### Firebase

Không dùng Firebase Admin SDK cho Java, vì SDK này yêu cầu credentials trong khi hệ thống hiện chỉ cần Project ID. Bản Java tự kiểm ID token bằng Nimbus JOSE + JWT:

- Chữ ký RS256 theo khoá công khai tại `https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com`, có cache.
- `iss` = `https://securetoken.google.com/<projectId>`, `aud` = `<projectId>`, `exp` chưa qua, `iat` và `auth_time` không ở tương lai quá 60 giây, `sub` khác rỗng.
- Lấy `phone_number` và `auth_time`, các kiểm tra sau đó giữ như `verifyFirebase`.
- Khi `FIREBASE_AUTH_EMULATOR_HOST` có giá trị và không phải production, chấp nhận token không ký của emulator. Production luôn bỏ qua biến này.

Đặt sau một interface `FirebaseVerifier` để test thay được, tương đương `firebaseVerifier.verify`.

Token của emulator không có chữ ký nên không chứng minh được phần kiểm tra RS256. Vì vậy phần này phải có test tự động: tự sinh khoá RSA trong test, kiểm tra token đúng được nhận và token sai chữ ký, sai `iss`, sai `aud`, hết hạn bị từ chối. Thử tay dùng số thử trên Firebase Console, tức là token ký thật.

### Tác vụ chạy nền

- `autoCancelNoShows`: chạy khi khởi động và mỗi phút (`@Scheduled`).
- Xoá `verification_codes` quá hạn hơn 1 giờ: mỗi 10 phút.

### Seed và chế độ demo

- `seed` và `demoClinic` chuyển sang Java, không chạy lại nếu đã có, giữ nguyên quy tắc `SEED_*`.
- Không có MySQL trong bộ nhớ. H2 không thay được vì khác cột sinh tự động và collation. Chế độ demo dùng database riêng `chuctu_demo`, xoá và tạo lại mỗi lần khởi động, không bao giờ đụng database chính, cấm khi production.

### Kiểm thử

- JUnit 5, Spring Boot Test, MockMvc hoặc HTTP client, chạy trên MySQL thật. H2 không được dùng, vì các phần rủi ro nhất là hành vi riêng của MySQL.
- Dùng database `chuctu_test` trên MySQL cài ở máy, kết nối qua biến môi trường. Xoá dữ liệu và seed lại trước mỗi test.
- Mỗi file trong `backend/test` có lớp test Java tương ứng. Mỗi test Java ghi tên test Node gốc để đối chiếu đủ 196.
- Bắt buộc có: 9 người đặt đồng thời vào khung còn 6 chỗ (đúng 6 thành công, 3 nhận `SLOT_FULL`) chạy bằng nhiều luồng thật, và mời đồng thời không làm trùng ghế.
- Test không bao giờ gửi email hay SMS thật.

### Docker và tài liệu

- `Dockerfile` nhiều tầng, `docker-compose.yml` gồm `mysql:8.4` và `server`, thay thế cấu hình `mongo` hiện tại.
- Máy hiện chưa có Docker và người dùng chưa cài. Phần này được viết nhưng chưa chạy thử, và phải báo rõ khi bàn giao.

### Triển khai sau này

Chưa chọn nơi đặt MySQL. Bất kỳ nơi nào được chọn đều phải là **MySQL 8.0 trở lên** (không dùng MySQL 5.7 hay MariaDB), vì thiết kế dựa vào collation `utf8mb4_0900_as_ci`. `frontend/vercel.json` đang chuyển `/api` tới một backend trên Render. Đổi backend trên môi trường đó không nằm trong đợt này, nên chưa sửa file này.
- `README.md` viết lại cho bản Java, giữ các phần đối chiếu BR và cấu hình Firebase.

## 5. Thứ tự thực hiện

1. Công cụ: đã có JDK 21 (Temurin 21.0.13) và MySQL 8.4.13 (service `MySQL84`, cổng 3306). Người dùng tự điền `backend-java/.env` (tài khoản MySQL, `JWT_SECRET`, SMTP, Firebase). Tài khoản MySQL dùng khi phát triển cần quyền tạo và xoá `chuctu_demo`, `chuctu_test`.
2. Tạo project, cấu hình, Flyway, định dạng lỗi và JSON, phiên, phân quyền.
3. Tài khoản, OTP, email, Firebase.
4. Hồ sơ bệnh nhân.
5. Đặt lịch, khung giờ, tự huỷ.
6. Hàng đợi.
7. Bệnh án.
8. Bài viết, ảnh, tài khoản Admin, dashboard, API công khai.
9. Seed, demo.
10. Nối frontend và thử toàn bộ luồng trên giao diện thật.
11. Docker, README, cập nhật nhắc đến Node trong tài liệu frontend nếu có.
12. Bỏ `backend/` chỉ khi người dùng đồng ý.

Mỗi bước phải có test chạy qua trước khi sang bước sau.

## 6. Điều kiện hoàn thành

- Toàn bộ test Java chạy qua, đủ tương ứng 196 test Node.
- So sánh JSON response của Node và Java trên cùng kịch bản chính (đăng ký, đặt lịch, check-in, hàng đợi, bệnh án, bài viết) không khác ngoài giá trị ID và thời gian.
- Thử tay trên frontend: đặt lịch, tiếp nhận, mời vào khám, lưu kết quả, quản lý bài viết, OTP email qua SMTP thật, đăng nhập Firebase bằng số thử trên Console.
- `backend-java/.env` do người dùng tự điền các giá trị bí mật. Không đưa mật khẩu SMTP vào chat.

## 7. Để người thực hiện quyết định

Tên package, chia lớp, DTO, cách dùng MapStruct hay Lombok, thư viện JWT, cách tổ chức migration Flyway, cách viết các truy vấn thống kê.

## 8. Các quyết định của người viết tài liệu

Người dùng đã đồng ý với: thư mục `backend-java/`, Java 21, Maven, Spring Data JPA, Flyway, ID số tự tăng trả về dạng chuỗi, ảnh giữ trong database, không thêm tính năng, không chuyển dữ liệu.

Các quyết định khác là do người viết chọn, chưa được người dùng duyệt riêng: collation `utf8mb4_0900_as_ci`, MySQL 8.4, bỏ cookie cũ `ct_staff`, tự kiểm token Firebase thay vì dùng Admin SDK, dùng `@Transactional` thay code bù trừ, chế độ demo bằng database riêng, test trên MySQL cài ở máy, thử Firebase bằng số thử trên Console kèm test tự động với khoá RSA tự sinh.
