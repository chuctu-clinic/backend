# Phòng khám Chúc Từ – Backend (Spring Boot + MySQL)

API cho website quản lý tiếp nhận và đặt lịch khám của Phòng chẩn trị Y học cổ truyền từ thiện Chúc Từ. Giao diện (trang khách và khu nội bộ) nằm ở repo [chuctu-clinic/frontend](https://github.com/chuctu-clinic/frontend).

Đây là bản viết lại của backend Node.js + MongoDB (thư mục `backend/`), giữ nguyên mọi đường dẫn `/api`, mã lỗi, thông báo tiếng Việt và dạng JSON. Bản Node vẫn là chuẩn đối chiếu.

| Phần | Công nghệ |
| --- | --- |
| API | Java 21, Spring Boot 4, REST |
| Cơ sở dữ liệu | MySQL 8.0 trở lên (khuyên dùng 8.4), Flyway, JPA/Hibernate |
| Kiểm thử | JUnit + MockMvc trên MySQL thật (database `chuctu_test`) |

## Yêu cầu

- JDK 21 (Maven Wrapper `./mvnw` đi kèm, không cần cài Maven).
- **MySQL 8.0 trở lên**. Database phải dùng `utf8mb4` và collation `utf8mb4_0900_as_ci` (collation này chỉ có từ MySQL 8.0).

## Chạy thử

1. Tạo tài khoản và các database bằng `scripts/setup-db.sql`, chạy một lần bằng tài khoản root. Sửa `<mật khẩu tự chọn>` trong file trước khi chạy (không commit mật khẩu thật):

   ```bash
   mysql -u root -p < scripts/setup-db.sql
   ```

   Script tạo người dùng `chuctu`, các database `chuctu` (chạy thật), `chuctu_test` (kiểm thử) và cấp quyền cho cả `chuctu_demo` (chế độ demo, tự tạo khi chạy).

2. Chép `.env.example` thành `.env`, điền `DB_PASSWORD` là mật khẩu vừa đặt. `.env` đã nằm trong `.gitignore`.

3. Chạy:

   ```bash
   ./mvnw spring-boot:run      # API ở http://localhost:4000/api
   ```

   Khởi động lần đầu, Flyway tạo bảng, rồi server nạp tài khoản Admin, Tiếp tân và 4 trang thông tin nếu chưa có. Ứng dụng đọc `.env` ở thư mục chạy; biến môi trường của hệ điều hành được ưu tiên hơn `.env`.

Để dùng giao diện, chạy thêm repo frontend (`npm run dev` ở đó, mở http://localhost:5173). Vite bên frontend chuyển `/api` sang cổng 4000 này.

Tài khoản mẫu (khi phát triển; đổi bằng các biến `SEED_*` trong `.env`, xem `.env.example`):

| Vai trò | Đăng nhập |
| --- | --- |
| Admin | `admin@chuctu.vn` hoặc `0900000001`, mật khẩu `ChucTu@2026` (trang `/noi-bo/dang-nhap`) |
| Tiếp tân | `letan@chuctu.vn` hoặc `0900000002`, mật khẩu `ChucTu@2026` |
| Người dùng | Số điện thoại `0912345678` (trang `/dang-nhap`), chỉ có khi chạy chế độ demo |

Người dùng đăng nhập bằng số điện thoại, không dùng mật khẩu. Khi phát triển (`SMS_PROVIDER=console`), mã 6 số được in ra log server và hiện luôn trên màn hình nhập mã.

### Chế độ demo

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Profile `demo` (thay cho MongoDB trong bộ nhớ của `npm run dev:demo`) dùng database riêng `chuctu_demo`: **mỗi lần khởi động xoá và tạo lại database này**, nạp tài khoản mẫu và dữ liệu phòng khám mẫu. Chỉ đụng đúng tên `chuctu_demo` (cùng máy chủ và tham số với `DB_URL`), và bị từ chối khi `NODE_ENV=production`.

### Nạp dữ liệu, không chạy server

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--seed
```

Nạp tài khoản Admin, Tiếp tân và 4 trang thông tin vào database của `DB_URL` rồi thoát (tương đương `npm run seed`). Chạy lại không sao: chỉ tạo cái còn thiếu.

## Kiểm thử

```bash
./mvnw test        # toàn bộ bộ kiểm thử
./mvnw verify      # test + đóng gói
```

Kiểm thử chạy trên MySQL thật, **không dùng H2**. Chúng lấy `DB_URL` đã nạp (từ `.env` hoặc biến môi trường) rồi đổi tên database thành `chuctu_test`, nên `DB_URL` phải trỏ tới database `chuctu` và tài khoản `DB_USERNAME` phải có quyền trên `chuctu_test` (script `setup-db.sql` đã cấp). Kiểm thử xoá và nạp lại dữ liệu trong `chuctu_test`, không đụng database `chuctu`.

- Chỉ chạy một lần kiểm thử tại một thời điểm, vì mọi lần chạy dùng chung `chuctu_test`.
- Kiểm thử không gửi email hay SMS thật.
- Mỗi test chuyển từ bản Node có `@DisplayName` đúng bằng tên `it(...)` của Node; test mới của bản Java có tiền tố `[java] `.

## Chạy bằng Docker

```bash
cp .env.example .env     # rồi điền ba biến bên dưới
docker compose up --build
```

Trong `.env` cần có cả ba biến sau; thiếu biến nào thì `docker compose` báo lỗi ngay, không khởi động rồi lặp lại việc tự khởi động lại:

- `DB_PASSWORD`: mật khẩu tài khoản MySQL `chuctu` (compose tạo tài khoản và database `chuctu` theo mật khẩu này ở lần đầu; đổi sau đó thì phải xoá volume hoặc đổi trong MySQL).
- `JWT_SECRET`: chuỗi ngẫu nhiên, ít nhất 32 ký tự (production).
- `SEED_ADMIN_PASSWORD`: mật khẩu Admin tạo ở lần chạy đầu, khác mật khẩu mẫu, ít nhất 8 ký tự.

`docker-compose.yml` chạy `mysql` (MySQL 8.4, có healthcheck, dữ liệu lưu ở volume `mysql-data`, không mở cổng ra máy chủ) và `server` (API ở cổng 4000; đổi cổng trên máy bằng `HOST_PORT`, ví dụ `HOST_PORT=4300`). `Dockerfile` nhiều tầng: build bằng JDK 21, chạy bằng JRE 21 với người dùng không phải root. Mỗi lần khởi động, server tự nạp tài khoản Admin, Tiếp tân và 4 trang thông tin nếu chưa có. Không nạp dữ liệu mẫu.

- Server chạy chế độ production nên mã 6 số không hiện trên màn hình. Xem mã trong log: `docker compose logs -f server`.
- Tiếp tân chỉ được tạo khi có `SEED_RECEPTIONIST_PASSWORD`; không thì Admin tự thêm ở Quản lý tài khoản.
- Biến `SEED_*` chỉ dùng khi tài khoản chưa có. Đổi mật khẩu tài khoản đã có thì đổi trong ứng dụng.
- Compose luôn đặt `SMS_PROVIDER=console` và `EMAIL_PROVIDER=console`. Muốn dùng Firebase hay SMTP, thêm các biến tương ứng vào mục `environment` của `server`.
- Xoá cả dữ liệu: `docker compose down -v`.

Đã thử: `docker compose config`, `docker compose up --build` (MySQL khoẻ, server khởi động, đăng nhập Admin bằng tài khoản seed trả 200, tiến trình chạy bằng người dùng không phải root), rồi `docker compose down -v`. Lần thử dùng SMS/email `console` và mật khẩu giả.

## Triển khai

Repo này chỉ phục vụ API dưới `/api`. Giao diện có thể gọi API theo hai cách:

1. **Cùng địa chỉ** (khuyên dùng): nginx của frontend chuyển `/api` sang backend (biến `API_UPSTREAM` bên frontend). Cookie phiên là cookie cùng trang, không cần cấu hình thêm.
2. **Khác địa chỉ**: frontend build với `VITE_API_URL` trỏ về backend. Khi đó đặt `CLIENT_ORIGIN` là địa chỉ frontend. Khi chạy production, cookie phiên có `SameSite=None; Secure` nên cả hai bên phải dùng HTTPS.

## Cấu hình (`.env`)

Chép `.env.example` thành `.env`. Tên biến giữ như bản Node, trừ `MONGODB_URI` được thay bằng ba biến `DB_*`.

| Biến | Ý nghĩa |
| --- | --- |
| `PORT`, `NODE_ENV` | Cổng (mặc định 4000) và chế độ chạy. `NODE_ENV=production` bật các kiểm tra nghiêm hơn (JWT_SECRET, mật khẩu Admin) và cookie `Secure`. |
| `DB_URL` | Địa chỉ JDBC MySQL, dạng `jdbc:mysql://host:3306/chuctu`. Kiểm thử và chế độ demo tự đổi tên database trong URL này. |
| `DB_USERNAME`, `DB_PASSWORD` | Tài khoản MySQL (tạo bằng `scripts/setup-db.sql`). |
| `JWT_SECRET` | Khoá ký phiên đăng nhập. Khi chạy production phải là chuỗi ngẫu nhiên, ít nhất 32 ký tự. |
| `CLIENT_ORIGIN` | Địa chỉ frontend được gọi API (CORS); nhiều địa chỉ thì ngăn bằng dấu phẩy. Cũng dùng để chống CSRF: yêu cầu thay đổi dữ liệu từ trang có Origin khác các địa chỉ này và khác tên miền của server bị từ chối 403. Frontend khác địa chỉ với backend thì phải có tên ở đây. |
| `SEED_ADMIN_*`, `SEED_RECEPTIONIST_*` | Tài khoản tạo sẵn khi nạp dữ liệu (số điện thoại, email, tên, mật khẩu), chỉ tạo nếu chưa có. Production bắt buộc `SEED_ADMIN_PASSWORD`. |
| `SMS_PROVIDER` | `console` (in mã ra log) hoặc `firebase` (Firebase Phone Auth, xem bên dưới). |
| `EMAIL_PROVIDER` | Gửi mã qua email cho quên mật khẩu và đổi email của nhân viên: `console` (in mã ra log, khi phát triển mã hiện luôn trên hộp thoại; production báo "liên hệ quản trị viên") hoặc `smtp` (gửi thật). |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`, `MAIL_FROM` | Bắt buộc khi `EMAIL_PROVIDER=smtp`, thiếu thì server không khởi động. Gmail: `smtp.gmail.com`, `587`, địa chỉ Gmail, Mật khẩu ứng dụng; `MAIL_FROM` là chính địa chỉ Gmail đó. Không đưa giá trị thật vào git. |
| `EMAIL_DEV_CODE` | Mã mẫu 6 số cho mã gửi qua email khi phát triển (`EMAIL_PROVIDER=console`). Bỏ trống thì là `123456`. Kiểm thử và production luôn dùng mã ngẫu nhiên. |
| `FIREBASE_PROJECT_ID`, `FIREBASE_API_KEY`, `FIREBASE_AUTH_DOMAIN`, `FIREBASE_APP_ID` | Cấu hình Web app Firebase (giá trị công khai), chỉ dùng khi `SMS_PROVIDER=firebase`. |
| `FIREBASE_AUTH_EMULATOR_HOST` | Ví dụ `127.0.0.1:9099`: dùng Firebase Auth Emulator, không gửi SMS thật. |
| `FIREBASE_TEST_NUMBERS_ONLY` | `1`: chỉ dùng số thử trên Firebase Console, bỏ qua reCAPTCHA. Không có tác dụng khi production. |
| `HOST_PORT` | Chỉ cho Docker Compose: cổng trên máy chủ nối với cổng 4000 của container (mặc định 4000). |

Trạng thái kiểm chứng: ngày 03/11/2026 đã thử tay giao diện frontend với bản Java, gửi email thật qua Gmail SMTP và gửi mã qua Firebase thật (số thử). Kết quả xem `docs/ket-qua-doi-chieu.md` mục 4. Kiểm thử tự động chỉ dùng nhà cung cấp `console` và giả lập.

## Cấu trúc mã nguồn

```
src/main/java/vn/chuctu/clinic/   mã nguồn, mỗi nhóm nghiệp vụ một package (auth, patient, appointment, queue, post, account, seed, common...)
src/main/resources/db/migration/  Flyway: V1__schema.sql (toàn bộ bảng) và các bản sau đó
src/test/                         kiểm thử theo Business Rule, chạy trên MySQL thật
scripts/                          setup-db.sql, check-test-parity.mjs, compare-responses.mjs
docs/ket-qua-doi-chieu.md         kết quả đối chiếu với bản Node
```

## Đối chiếu với tài liệu

Các quy tắc nghiệp vụ được kiểm tra ở backend; giao diện ẩn hoặc hiện nút chỉ để hỗ trợ người dùng.

- **BR.02–BR.03**: mã 6 số lưu dạng băm, hết hạn sau 5 phút. Mỗi số điện thoại nhận tối đa 3 mã mỗi giờ. Gửi lại cùng loại mã phải chờ 60 giây. Nhập sai tối đa 5 lần. Thiết bị đã xác nhận được nhận diện qua cookie thiết bị.
- **BR.14–BR.15, QAs6**: mỗi khung giờ có tối đa 6 phiếu. Chỗ được giữ bằng một lệnh cập nhật nguyên tử trên bảng đếm chỗ theo khung giờ, nên nhiều người đặt cùng lúc cũng không vượt. Đã có bài kiểm thử 9 người đặt đồng thời: 6 người được nhận, 3 người bị báo "đã đủ chỗ".
- **BR.17**: người dùng chỉ thấy ba trạng thái Đã đặt lịch, Đã hoàn thành, Đã huỷ.
- **BR.28–BR.30**: hàng đợi có 5 ghế, nhóm Ưu tiên đứng trước nhóm Thường, trong mỗi nhóm xếp theo giờ check-in. Có thể bật tự động mời người kế tiếp vào ghế trống. Hoàn tác vào khám thì người đó quay lại hàng chờ, còn ghế trống được nhường cho người kế tiếp.
- **BR.32–BR.33**: mỗi yêu cầu đều tải lại tài khoản, nên khoá tài khoản hoặc đổi vai trò có hiệu lực ngay. Tài khoản nội bộ dùng mật khẩu tạm phải đổi mật khẩu trước khi làm việc.
- **BR.38**: hồ sơ đã có lịch sử khám không bị xoá cứng. Người dùng xoá hồ sơ như vậy thì hồ sơ chỉ được tách khỏi tài khoản.

### Cách hiểu tài liệu ở một số điểm chưa rõ

Giữ nguyên như bản Node:

1. **Ngày được đặt trực tuyến**: BR.13 ghi "1 đến 30 ngày tới", nhưng prototype cho chọn cả hôm nay và BR.14 chỉ chặn "khung đã bắt đầu". Mã hiện cho đặt từ hôm nay đến 30 ngày tới, chỉ với các khung chưa bắt đầu.
2. **"Đến đúng khung giờ"**: được tính là check-in từ 15 phút trước giờ bắt đầu đến hết khung. Khách đặt hộ ngay trong ngày được tính là khách đến trực tiếp, xếp nhóm Thường.
3. **Khung giờ**: 8:00, 9:00, 10:00, 11:00, 13:00, 14:00, 15:00, 16:00, giống prototype (nghỉ trưa 12:00). Giờ nghiệp vụ tính theo Asia/Ho_Chi_Minh.
4. **Trang Giới thiệu, Dịch vụ, Hướng dẫn, Liên hệ**: là bài viết thuộc chuyên mục "Trang thông tin", có đường dẫn cố định. Admin sửa được nội dung nhưng không xoá được.
5. **Bảng hỗ trợ**: bộ đếm để sinh mã bệnh nhân `CT-BN-26-0001` và mã phiếu `CT-PK-26-2010-001`, bộ đếm chỗ trong khung giờ, và cài đặt tự động mời.

Khác bản Node (theo thiết kế ở spec): ID trong JSON vẫn là chuỗi nhưng là số thứ tự của MySQL thay cho ObjectId; thời điểm luôn có 3 chữ số mili giây và `Z`; `dob` trả `YYYY-MM-DDT00:00:00.000Z`; `slotDate`, `visitDate`, `followUpDate` trả `YYYY-MM-DD`; không còn cookie `ct_staff` cũ.

## Đối chiếu với bản Node

Hai script (cần Node.js 20 trở lên chỉ để chạy script, không cần `npm install`) nằm trong `scripts/`. Chúng giả định thư mục `backend/` (bản Node) nằm cạnh `backend-java/`. Kết quả lần chạy cuối và quy tắc bỏ qua: `docs/ket-qua-doi-chieu.md`.

**Đối chiếu tên test** (mọi `it(...)` của Node có `@DisplayName` cùng tên bên Java; thoát khác 0 nếu thiếu tên hoặc còn `@Disabled`):

```bash
node scripts/check-test-parity.mjs [thư-mục-test-node]
```

**Đối chiếu phản hồi** (chạy cùng một kịch bản 304 bước trên hai server, so mã HTTP, header, cookie và JSON). Điều kiện:

- Cả hai server **vừa khởi động mới** ở chế độ demo (dữ liệu demo reset khi khởi động; chạy lần hai trên server cũ sẽ hết chỗ hoặc đủ ghế).
- Cả hai chạy trên `127.0.0.1` (script từ chối địa chỉ khác, để khỏi đụng cookie `ct_session` của `localhost`).
- Chạy **trước 16:00 của một ngày làm việc**, vì kịch bản đặt lịch hôm nay vào khung 16:00.
- Cả hai dùng `SMS_PROVIDER=console` và `EMAIL_PROVIDER=console`.

```bash
# Node, chế độ demo, chạy từ backend/
PORT=4100 NODE_ENV=development SMS_PROVIDER=console EMAIL_PROVIDER=console CLIENT_ORIGIN=http://127.0.0.1:5180 node src/index.js --demo
# Java, profile demo (database chuctu_demo), chạy từ backend-java/
PORT=4200 NODE_ENV=development SMS_PROVIDER=console EMAIL_PROVIDER=console CLIENT_ORIGIN=http://127.0.0.1:5180 ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
# Đối chiếu
node scripts/compare-responses.mjs [--verbose] [--show <đoạn nhãn bước>]
```

## Gửi mã xác nhận qua Firebase

Mục này chỉ mô tả cấu hình; không đưa giá trị thật vào git. Đã thử với Firebase thật bằng số thử trên Firebase Console (xem `docs/ket-qua-doi-chieu.md` mục 4).

Đặt `SMS_PROVIDER=firebase`. Trình duyệt gửi và xác nhận mã bằng Firebase Phone Auth (kèm reCAPTCHA ẩn), rồi gửi ID token cho server; server kiểm token (chỉ cần Project ID, không cần khoá service account), đối chiếu số điện thoại và lần xin mã, rồi cấp phiên đăng nhập như cũ. Giới hạn 3 mã mỗi giờ và chờ 60 giây (BR.03) vẫn do server giữ.

Cài đặt trên Firebase Console:

1. Tạo project, thêm **Web app**, chép các giá trị `apiKey`, `authDomain`, `projectId`, `appId` vào `.env`.
2. **Authentication → Sign-in method**: bật **Phone**.
3. **Authentication → Settings → Authorized domains**: thêm `localhost` và tên miền thật.
4. **SMS region policy**: cho phép Việt Nam.

Thử không tốn tiền, không gửi SMS thật:

- **Số điện thoại thử**: Authentication → Sign-in method → Phone → *Phone numbers for testing*, thêm số kèm mã cố định. Số phải có đầu số di động hợp lệ (03, 05, 07, 08, 09).
- **Firebase Auth Emulator**: `npx firebase-tools emulators:start --only auth --project demo-chuctu`, đặt `FIREBASE_PROJECT_ID=demo-chuctu` và `FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099` (các biến web khác điền giá trị bất kỳ).

## Gửi mã qua email (SMTP)

Mục này chỉ mô tả cấu hình. Đặt `EMAIL_PROVIDER=smtp` và điền `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`, `MAIL_FROM` trong `.env` (không đưa vào git, không dán vào chat hay log). Với Gmail dùng Mật khẩu ứng dụng. Cổng 465 dùng kết nối bảo mật trực tiếp. `MAIL_FROM` phải là địa chỉ email hoặc dạng `Tên <địa chỉ>`, và địa chỉ nên trùng `SMTP_USER`. Chỉ ghi tên hiển thị thì gửi lỗi. Đã thử gửi thật qua Gmail.

## Việc còn lại

- **Thử tay với frontend và nhà cung cấp thật**: chưa làm (Firebase thật, SMTP thật, giao diện nối với bản Java).
- **Nhà cung cấp SMS khi chạy thật**: Firebase dùng để thử nghiệm. Khi chạy thật nên cân nhắc nhà cung cấp SMS trong nước có tên người gửi (brandname).
- **Nơi triển khai**: chưa chọn, chưa có CI/CD.
- **Giới hạn số lần thử đăng nhập nội bộ**: chưa có.
- Thư mục `backend/` (Node) và `frontend/vercel.json` chưa đổi; chờ quyết định sau khi xem kết quả đối chiếu.
