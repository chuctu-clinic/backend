# Phòng khám Chúc Từ – Backend

API cho website quản lý tiếp nhận và đặt lịch khám của Phòng chẩn trị Y học cổ truyền từ thiện Chúc Từ. Mã nguồn dựng theo bộ tài liệu **Phòng khám Chúc Từ ver 2** (Concept of Operations, Architecture Driver/Design, Database Analysis & Design, Function List, Business Rule).

Giao diện nằm ở repo [chuctu-clinic/frontend](https://github.com/chuctu-clinic/frontend).

| Phần | Công nghệ |
| --- | --- |
| Backend | Node.js + Express + TypeScript, REST API |
| Cơ sở dữ liệu | MongoDB / MongoDB Atlas (Mongoose) |
| Kiểm thử API | `node:test` + Supertest + MongoDB trong bộ nhớ |

## Chạy thử

Yêu cầu Node.js 22.18 trở lên (khuyên dùng Node.js 24). Mã nguồn viết bằng TypeScript; Node.js chạy thẳng file `.ts` (type stripping) nên không có bước build.

```bash
npm install
npm run dev
```

API chạy ở http://localhost:4000/api. Chạy kèm repo frontend (`npm run dev` bên đó) rồi mở http://localhost:5173: Vite chuyển tiếp `/api` sang cổng 4000.

Chưa đặt `MONGODB_URI` thì server tự chạy MongoDB trong bộ nhớ và nạp dữ liệu mẫu. Dữ liệu này mất khi tắt server. Lần chạy đầu tiên sẽ tải bộ cài MongoDB về máy, mất khoảng 1–2 phút.

Tài khoản mẫu:

| Vai trò | Đăng nhập |
| --- | --- |
| Admin | `admin@chuctu.vn` hoặc `0900000001`, mật khẩu `ChucTu@2026` (trang `/noi-bo/dang-nhap`) |
| Tiếp tân | `letan@chuctu.vn` hoặc `0900000002`, mật khẩu `ChucTu@2026` |
| Người dùng | Số điện thoại `0912345678` (trang `/dang-nhap`) |

Người dùng đăng nhập bằng số điện thoại, không dùng mật khẩu. Khi phát triển (`SMS_PROVIDER=console`), mã 6 số được in ra log server và hiện luôn trên màn hình nhập mã.

### Các lệnh khác

```bash
npm test          # chạy kiểm thử API (34 bài: tài khoản, đặt lịch, hàng đợi)
npm run typecheck # kiểm tra kiểu bằng tsc (chế độ strict)
npm start         # chạy server (production)
npm run seed      # nạp tài khoản Admin, Tiếp tân và 4 trang thông tin vào MONGODB_URI
```

## Chạy bằng Docker

```bash
docker compose up --build
```

`docker-compose.yml` chạy 2 container:

| Container | Nội dung |
| --- | --- |
| `mongo` | MongoDB 7, dữ liệu lưu ở volume `mongo-data` (còn nguyên khi tắt container). |
| `server` | API ở cổng 4000 (`Dockerfile`). Mỗi lần khởi động tự nạp tài khoản Admin, Tiếp tân và 4 trang thông tin nếu chưa có. Không nạp dữ liệu mẫu. |

Sau đó chạy container giao diện ở repo frontend (`docker compose up --build`), mở http://localhost:8080.

- Server chạy chế độ production nên mã 6 số không hiện trên màn hình. Xem mã trong log: `docker compose logs -f server`.
- Đặt `JWT_SECRET` (và `CLIENT_ORIGIN` nếu giao diện không ở `http://localhost:8080`) trong file `.env` cạnh `docker-compose.yml` trước khi chạy thật.
- Tài khoản Admin và Tiếp tân được tạo với mật khẩu mẫu `ChucTu@2026`. Đổi mật khẩu ngay sau lần đăng nhập đầu.
- Cookie phiên ở production có cờ `Secure`. Trình duyệt chấp nhận cookie này trên `http://localhost`, nhưng nếu mở bằng IP hoặc tên miền thì cần HTTPS.

## Triển khai (CI/CD lên Render)

`.github/workflows/ci-cd.yml`:

| Sự kiện | Việc chạy |
| --- | --- |
| Pull request | `npm run typecheck`, `npm test`, thử build image (không đẩy) |
| Push lên `main` | `npm run typecheck`, `npm test` → build image → đẩy lên `ghcr.io/chuctu-clinic/backend` (tag `latest` và mã commit) → gọi Render Deploy Hook kèm đúng image vừa build |

Render không tự build mà chỉ kéo image về chạy. Thiết lập một lần:

1. **Cho Render kéo image từ GHCR.** Sau lần chạy workflow đầu tiên, package `backend` xuất hiện ở trang Packages của tổ chức `chuctu-clinic`. Chọn một trong hai:
   - Đặt package ở chế độ Public, rồi xoá khối `creds` trong `render.yaml`; hoặc
   - Tạo GitHub token (classic) có quyền `read:packages`, rồi trên Render vào **Settings → Container Registry Credentials** thêm credential tên `ghcr-chuctu` (registry GitHub, username GitHub, token vừa tạo).
2. **Tạo dịch vụ.** Trên Render chọn **New → Blueprint**, trỏ vào repo này (Render đọc `render.yaml`), nhập `MONGODB_URI` (MongoDB Atlas). `JWT_SECRET` được sinh ngẫu nhiên. Nếu MongoDB Atlas giới hạn IP thì cho phép `0.0.0.0/0` hoặc dải IP ra của Render.
3. **Nối GitHub với Render.** Vào dịch vụ `chuctu-backend` → **Settings → Deploy Hook**, chép URL. Trên GitHub vào **Settings → Secrets and variables → Actions** của repo, thêm secret `RENDER_DEPLOY_HOOK` với URL đó.

Từ đó mỗi lần push lên `main`, workflow tự build và triển khai. Chưa có secret thì bước triển khai được bỏ qua (có cảnh báo), các bước khác vẫn chạy. Muốn quay lại bản cũ: vào Render → **Manual Deploy → Deploy an existing image** với tag là mã commit cũ, hoặc chạy lại workflow của commit đó.

- Nếu Render đặt cho dịch vụ địa chỉ khác `chuctu-backend.onrender.com` (trùng tên), sửa `API_UPSTREAM` của dịch vụ frontend cho khớp.
- Nếu frontend có địa chỉ khác `https://chuctu-frontend.onrender.com` (hoặc gắn tên miền riêng), sửa `CLIENT_ORIGIN`.
- Gói Free của Render tắt dịch vụ sau 15 phút không có truy cập; lần truy cập sau chờ khoảng 1 phút để khởi động lại.

## Cấu hình (`.env`)

Chép `.env.example` thành `.env`:

| Biến | Ý nghĩa |
| --- | --- |
| `MONGODB_URI` | Chuỗi kết nối MongoDB Atlas. Bắt buộc khi chạy production. |
| `JWT_SECRET` | Khoá ký phiên đăng nhập. Bắt buộc đổi khi chạy production. |
| `CLIENT_ORIGIN` | Địa chỉ frontend được gọi API (CORS); nhiều địa chỉ thì ngăn bằng dấu phẩy. |
| `SMS_PROVIDER` | `console` (in mã ra log) hoặc `firebase` (chưa nối, xem bên dưới). |

## Cấu trúc mã nguồn

```
src/models/       Mongoose: users, verificationCodes, trustedDevices, posts, patients,
                  appointments, checkins, medicalRecords (+ counters, slotCounters, settings)
src/middleware/   xác thực phiên, phân quyền USER / RECEPTIONIST / ADMIN, xử lý lỗi
src/services/     quy tắc nghiệp vụ: auth, patient, appointment, queue, medicalRecord, post, account
src/routes/       /api/auth, /api/me (người dùng), /api/staff (tiếp tân + admin), /api/admin
src/shared/       constants.ts: vai trò, trạng thái phiếu, nội dung khám, giới tính, quan hệ.
                  Repo frontend có bản giống hệt ở shared/src/constants.ts, sửa thì sửa cả hai.
src/types/        bổ sung kiểu cho Express (req.user)
test/             kiểm thử theo Business Rule
Dockerfile, docker-compose.yml
render.yaml       Render Blueprint (dịch vụ chạy từ image GHCR)
.github/workflows CI/CD: kiểm thử, build image, triển khai lên Render
```

## Đối chiếu với tài liệu

Đã làm đủ 59 chức năng trong Function List (F.1.1 – F.10.5). Các quy tắc nghiệp vụ được kiểm tra ở Backend; giao diện ẩn hoặc hiện nút chỉ để hỗ trợ người dùng.

- **BR.02–BR.03**: mã 6 số lưu dạng băm, hết hạn sau 5 phút. Mỗi số điện thoại nhận tối đa 3 mã mỗi giờ. Gửi lại cùng loại mã phải chờ 60 giây. Nhập sai tối đa 5 lần. Thiết bị đã xác nhận được nhận diện qua cookie thiết bị.
- **BR.14–BR.15, QAs6**: mỗi khung giờ có tối đa 6 phiếu. Chỗ được giữ bằng một lệnh cập nhật nguyên tử trên `slotCounters`, nên nhiều người đặt cùng lúc cũng không vượt. Đã có bài kiểm thử 9 người đặt đồng thời: 6 người được nhận, 3 người bị báo "đã đủ chỗ".
- **BR.17**: người dùng chỉ thấy ba trạng thái Đã đặt lịch, Đã hoàn thành, Đã huỷ.
- **BR.28–BR.30**: hàng đợi có 5 ghế, nhóm Ưu tiên đứng trước nhóm Thường, trong mỗi nhóm xếp theo giờ check-in. Có thể bật tự động mời người kế tiếp vào ghế trống. Hoàn tác vào khám thì người đó quay lại hàng chờ, còn ghế trống được nhường cho người kế tiếp.
- **BR.32–BR.33**: mỗi yêu cầu đều tải lại tài khoản, nên khoá tài khoản hoặc đổi vai trò có hiệu lực ngay. Tài khoản nội bộ dùng mật khẩu tạm phải đổi mật khẩu trước khi làm việc.
- **BR.38**: hồ sơ đã có lịch sử khám không bị xoá cứng. Người dùng xoá hồ sơ như vậy thì hồ sơ chỉ được tách khỏi tài khoản.

### Cách hiểu tài liệu ở một số điểm chưa rõ

1. **Ngày được đặt trực tuyến**: BR.13 ghi "1 đến 30 ngày tới", nhưng prototype cho chọn cả hôm nay và BR.14 chỉ chặn "khung đã bắt đầu". Mã hiện cho đặt từ hôm nay đến 30 ngày tới, chỉ với các khung chưa bắt đầu. Chỉnh tại `rules.booking` trong `src/config.ts`.
2. **"Đến đúng khung giờ"**: được tính là check-in từ 15 phút trước giờ bắt đầu đến hết khung (`rules.queue.priorityEarlyMinutes`). Khách đặt hộ ngay trong ngày được tính là khách đến trực tiếp, xếp nhóm Thường.
3. **Khung giờ**: 8:00, 9:00, 10:00, 11:00, 13:00, 14:00, 15:00, 16:00, giống prototype (nghỉ trưa 12:00).
4. **Trang Giới thiệu, Dịch vụ, Hướng dẫn, Liên hệ**: là bài viết thuộc chuyên mục "Trang thông tin", có đường dẫn cố định. Admin sửa được nội dung nhưng không xoá được.
5. **Thêm collection hỗ trợ**: `counters` để sinh mã bệnh nhân `CT-BN-26-0001` và mã phiếu `CT-PK-26-2010-001`, `slotCounters` để đếm chỗ trong khung giờ, `settings` để lưu cài đặt tự động mời. Ngoài ra `checkins` có thêm trường `visitDate`, `posts` có thêm `slug` và `summary`, `patients` có thêm `birthYear` (tiếp tân chỉ hỏi năm sinh).

### Việc còn lại

- **Gửi mã qua Firebase**: Firebase Phone Auth tự sinh mã và gửi từ trình duyệt (cần reCAPTCHA), Backend không gửi hộ được. Cách đó khác với thiết kế hiện tại, nơi Backend sinh mã và lưu dạng băm. Nhóm cần chọn một trong hai hướng:
  - (a) Đổi sang luồng Firebase: trình duyệt xác thực rồi gửi ID token cho Backend kiểm bằng `firebase-admin`.
  - (b) Giữ thiết kế hiện tại, dùng một nhà cung cấp SMS có API phía server.

  Chỗ nối nằm ở `src/services/smsGateway.ts`.
- **Giới hạn số lần thử đăng nhập nội bộ**: chưa có.
