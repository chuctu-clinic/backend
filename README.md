# Phòng khám Chúc Từ – Backend

API cho website quản lý tiếp nhận và đặt lịch khám của Phòng chẩn trị Y học cổ truyền từ thiện Chúc Từ. Giao diện (trang khách và khu nội bộ) nằm ở repo [chuctu-clinic/frontend](https://github.com/chuctu-clinic/frontend).

| Phần | Công nghệ |
| --- | --- |
| API | Node.js + Express, REST |
| Cơ sở dữ liệu | MongoDB / MongoDB Atlas (Mongoose) |
| Kiểm thử | `node:test` + Supertest + MongoDB trong bộ nhớ |

## Chạy thử

Yêu cầu Node.js 20 trở lên.

```bash
npm install
npm run dev        # API ở http://localhost:4000/api
```

Chưa đặt `MONGODB_URI` thì server tự chạy MongoDB trong bộ nhớ và nạp dữ liệu mẫu. Dữ liệu này mất khi tắt server. Lần chạy đầu tiên sẽ tải bộ cài MongoDB về máy, mất khoảng 1–2 phút.

Để dùng giao diện, chạy thêm repo frontend (`npm run dev` ở đó, mở http://localhost:5173). Vite bên frontend chuyển `/api` sang cổng 4000 này.

Tài khoản mẫu:

| Vai trò | Đăng nhập |
| --- | --- |
| Admin | `admin@chuctu.vn` hoặc `0900000001`, mật khẩu `ChucTu@2026` (trang `/noi-bo/dang-nhap`) |
| Tiếp tân | `letan@chuctu.vn` hoặc `0900000002`, mật khẩu `ChucTu@2026` |
| Người dùng | Số điện thoại `0912345678` (trang `/dang-nhap`) |

Người dùng đăng nhập bằng số điện thoại, không dùng mật khẩu. Khi phát triển (`SMS_PROVIDER=console`), mã 6 số được in ra log server và hiện luôn trên màn hình nhập mã.

### Các lệnh khác

```bash
npm test           # kiểm thử API: tài khoản, đặt lịch, hàng đợi, bài viết, Firebase
npm start          # chạy API
npm run seed       # nạp tài khoản Admin, Tiếp tân và 4 trang thông tin vào MONGODB_URI
npm run dev:demo   # chạy với dữ liệu phòng khám mẫu
```

## Chạy bằng Docker

```bash
echo "JWT_SECRET=$(node -e "console.log(require('crypto').randomBytes(32).toString('hex'))")" > .env
docker compose up --build
```

`docker-compose.yml` chạy `mongo` (dữ liệu lưu ở volume `mongo-data`) và `server` (API ở cổng 4000). Mỗi lần khởi động, server tự nạp tài khoản Admin, Tiếp tân và 4 trang thông tin nếu chưa có. Không nạp dữ liệu mẫu.

- Server chạy chế độ production nên mã 6 số không hiện trên màn hình. Xem mã trong log: `docker compose logs -f server`.
- Tài khoản Admin và Tiếp tân được tạo với mật khẩu mẫu `ChucTu@2026`. Đổi mật khẩu ngay sau lần đăng nhập đầu.

## Triển khai

Repo này chỉ phục vụ API dưới `/api`. Giao diện có thể gọi API theo hai cách:

1. **Cùng địa chỉ** (khuyên dùng): nginx của frontend chuyển `/api` sang backend (biến `API_UPSTREAM` bên frontend). Cookie phiên là cookie cùng trang, không cần cấu hình thêm.
2. **Khác địa chỉ**: frontend build với `VITE_API_URL` trỏ về backend. Khi đó đặt `CLIENT_ORIGIN` là địa chỉ frontend. Khi chạy production, cookie phiên có `SameSite=None; Secure` nên cả hai bên phải dùng HTTPS.

## Cấu hình (`.env`)

Chép `.env.example` thành `.env`:

| Biến | Ý nghĩa |
| --- | --- |
| `MONGODB_URI` | Chuỗi kết nối MongoDB Atlas. Bắt buộc khi chạy production. |
| `JWT_SECRET` | Khoá ký phiên đăng nhập. Khi chạy production phải là chuỗi ngẫu nhiên, ít nhất 32 ký tự. |
| `CLIENT_ORIGIN` | Địa chỉ frontend được gọi API (CORS); nhiều địa chỉ thì ngăn bằng dấu phẩy. |
| `SMS_PROVIDER` | `console` (in mã ra log) hoặc `firebase` (Firebase Phone Auth, xem bên dưới). |
| `FIREBASE_PROJECT_ID`, `FIREBASE_API_KEY`, `FIREBASE_AUTH_DOMAIN`, `FIREBASE_APP_ID` | Cấu hình Web app Firebase (giá trị công khai), chỉ dùng khi `SMS_PROVIDER=firebase`. |
| `FIREBASE_AUTH_EMULATOR_HOST` | Ví dụ `127.0.0.1:9099`: dùng Firebase Auth Emulator, không gửi SMS thật. |

## Cấu trúc mã nguồn

```
src/models/       Mongoose: users, verificationCodes, trustedDevices, posts, patients,
                  appointments, checkins, medicalRecords, uploads (+ counters, slotCounters, settings)
src/middleware/   xác thực phiên, phân quyền USER / RECEPTIONIST / ADMIN, xử lý lỗi
src/services/     quy tắc nghiệp vụ: auth, patient, appointment, queue, medicalRecord, post, account
src/routes/       /api/auth, /api/me (người dùng), /api/staff (tiếp tân + admin), /api/admin
src/shared/       constants.js, phone.js: BẢN CHÉP từ repo frontend (shared/src/). Sửa bên nào thì chép sang bên kia.
test/             kiểm thử theo Business Rule
```

## Đối chiếu với tài liệu

Các quy tắc nghiệp vụ được kiểm tra ở backend; giao diện ẩn hoặc hiện nút chỉ để hỗ trợ người dùng.

- **BR.02–BR.03**: mã 6 số lưu dạng băm, hết hạn sau 5 phút. Mỗi số điện thoại nhận tối đa 3 mã mỗi giờ. Gửi lại cùng loại mã phải chờ 60 giây. Nhập sai tối đa 5 lần. Thiết bị đã xác nhận được nhận diện qua cookie thiết bị.
- **BR.14–BR.15, QAs6**: mỗi khung giờ có tối đa 6 phiếu. Chỗ được giữ bằng một lệnh cập nhật nguyên tử trên `slotCounters`, nên nhiều người đặt cùng lúc cũng không vượt. Đã có bài kiểm thử 9 người đặt đồng thời: 6 người được nhận, 3 người bị báo "đã đủ chỗ".
- **BR.17**: người dùng chỉ thấy ba trạng thái Đã đặt lịch, Đã hoàn thành, Đã huỷ.
- **BR.28–BR.30**: hàng đợi có 5 ghế, nhóm Ưu tiên đứng trước nhóm Thường, trong mỗi nhóm xếp theo giờ check-in. Có thể bật tự động mời người kế tiếp vào ghế trống. Hoàn tác vào khám thì người đó quay lại hàng chờ, còn ghế trống được nhường cho người kế tiếp.
- **BR.32–BR.33**: mỗi yêu cầu đều tải lại tài khoản, nên khoá tài khoản hoặc đổi vai trò có hiệu lực ngay. Tài khoản nội bộ dùng mật khẩu tạm phải đổi mật khẩu trước khi làm việc.
- **BR.38**: hồ sơ đã có lịch sử khám không bị xoá cứng. Người dùng xoá hồ sơ như vậy thì hồ sơ chỉ được tách khỏi tài khoản.

### Cách hiểu tài liệu ở một số điểm chưa rõ

1. **Ngày được đặt trực tuyến**: BR.13 ghi "1 đến 30 ngày tới", nhưng prototype cho chọn cả hôm nay và BR.14 chỉ chặn "khung đã bắt đầu". Mã hiện cho đặt từ hôm nay đến 30 ngày tới, chỉ với các khung chưa bắt đầu. Chỉnh tại `rules.booking` trong `src/config.js`.
2. **"Đến đúng khung giờ"**: được tính là check-in từ 15 phút trước giờ bắt đầu đến hết khung (`rules.queue.priorityEarlyMinutes`). Khách đặt hộ ngay trong ngày được tính là khách đến trực tiếp, xếp nhóm Thường.
3. **Khung giờ**: 8:00, 9:00, 10:00, 11:00, 13:00, 14:00, 15:00, 16:00, giống prototype (nghỉ trưa 12:00).
4. **Trang Giới thiệu, Dịch vụ, Hướng dẫn, Liên hệ**: là bài viết thuộc chuyên mục "Trang thông tin", có đường dẫn cố định. Admin sửa được nội dung nhưng không xoá được.
5. **Thêm collection hỗ trợ**: `counters` để sinh mã bệnh nhân `CT-BN-26-0001` và mã phiếu `CT-PK-26-2010-001`, `slotCounters` để đếm chỗ trong khung giờ, `settings` để lưu cài đặt tự động mời. Ngoài ra `checkins` có thêm trường `visitDate`, `posts` có thêm `slug` và `summary`, `patients` có thêm `birthYear` (tiếp tân chỉ hỏi năm sinh).

### Gửi mã xác nhận qua Firebase

Đặt `SMS_PROVIDER=firebase`. Trình duyệt gửi và xác nhận mã bằng Firebase Phone Auth (kèm reCAPTCHA ẩn), rồi gửi ID token cho server; server kiểm token bằng `firebase-admin` (chỉ cần Project ID, không cần khoá service account), đối chiếu số điện thoại và lần xin mã, rồi cấp phiên đăng nhập như cũ. Giới hạn 3 mã mỗi giờ và chờ 60 giây (BR.03) vẫn do server giữ.

Cài đặt trên Firebase Console:

1. Tạo project, thêm **Web app**, chép các giá trị `apiKey`, `authDomain`, `projectId`, `appId` vào `.env`.
2. **Authentication → Sign-in method**: bật **Phone**.
3. **Authentication → Settings → Authorized domains**: thêm `localhost` và tên miền thật.
4. **SMS region policy**: cho phép Việt Nam.

Thử không tốn tiền, không gửi SMS thật:

- **Số điện thoại thử**: Authentication → Sign-in method → Phone → *Phone numbers for testing*, thêm số kèm mã cố định (ví dụ `+84 999 000 001` / `123456`). Số phải có đầu số di động hợp lệ (03, 05, 07, 08, 09).
- **Firebase Auth Emulator**: `npx firebase-tools emulators:start --only auth --project demo-chuctu`, đặt `FIREBASE_PROJECT_ID=demo-chuctu` và `FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099` (các biến web khác điền giá trị bất kỳ). Mã gửi đi xem ở `http://127.0.0.1:9099/emulator/v1/projects/demo-chuctu/verificationCodes`.

### Việc còn lại

- **Nhà cung cấp SMS khi chạy thật**: Firebase dùng để thử nghiệm. Khi chạy thật nên cân nhắc nhà cung cấp SMS trong nước có tên người gửi (brandname), chỉ cần thêm một provider trong `src/services/smsGateway.js`.
- **Nơi triển khai**: chưa chọn, chưa có CI/CD.
- **Giới hạn số lần thử đăng nhập nội bộ**: chưa có.
