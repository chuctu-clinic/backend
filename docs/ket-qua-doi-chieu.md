# Kết quả đối chiếu bản Java với bản Node

Ngày chạy: 2026-11-03. Bản Node (`backend/`) là chuẩn. Hai script nằm trong `scripts/`.

## 1. Đối chiếu tên test (`scripts/check-test-parity.mjs`)

```
node scripts/check-test-parity.mjs
Đủ 192/196 (4 miễn: ...)        # thoát 0
```

Lấy mọi tên `it(...)` trong `backend/test/*.test.js` (chuỗi nháy đơn, nháy kép, template không có `${}`, và nối bằng `+`; tên không đọc được thì in ra và thoát khác 0), so với `@DisplayName` trong `src/test` (bỏ tên có tiền tố `[java] `). Script cũng thoát khác 0 nếu còn `@Disabled` ở bất kỳ đâu trong `src/test`, hoặc nếu một tên miễn không còn trong test Node.

4 test Node được miễn:

| Test Node | Lý do |
|---|---|
| `lấy chữ số, bỏ phần 84 khi dán cả số quốc tế` (phone.test.js) | kiểm `phoneDigits`, hàm chỉ chạy trên trình duyệt, server không gọi |
| `cho gõ dư tối đa 12 số để báo thừa thay vì cắt bớt` (phone.test.js) | kiểm `phoneDigits`/`phoneInputError`, như trên |
| `9 số sau +84 được thêm số 0, cùng quy tắc với server` (phone.test.js) | kiểm `toPhone`/`phoneInputError`, như trên |
| `cookie ct_staff cũ được chuyển thành ct_session` (auth.test.js) | spec bỏ cookie `ct_staff` |

## 2. Đối chiếu phản hồi (`scripts/compare-responses.mjs`)

Cách chạy (an toàn, chỉ nhận địa chỉ `127.0.0.1`):

```
# Node, chế độ demo (MongoDB trong bộ nhớ), chạy từ backend/
PORT=4100 NODE_ENV=development SMS_PROVIDER=console EMAIL_PROVIDER=console CLIENT_ORIGIN=http://127.0.0.1:5180 node src/index.js --demo
# Java, profile demo (database chuctu_demo), chạy từ backend-java/
PORT=4200 NODE_ENV=development SMS_PROVIDER=console EMAIL_PROVIDER=console CLIENT_ORIGIN=http://127.0.0.1:5180 ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
node scripts/compare-responses.mjs [--verbose] [--show <đoạn nhãn bước>]
```

Nên khởi động lại cả hai server trước mỗi lần chạy: dữ liệu demo reset khi khởi động, còn kịch bản đặt lịch vào các khung giờ cố định và dùng ghế trong phòng khám nên chạy lần hai trên server cũ sẽ hết chỗ hoặc đủ ghế. Kịch bản có đặt lịch hôm nay vào khung 16:00 nên chạy trước 16:00.

Kịch bản (304 bước, mỗi kịch bản một kho cookie riêng): công khai và lỗi chung (404, JSON hỏng, body quá lớn, chưa đăng nhập, sai vai trò); header bảo mật và CORS; người dùng (đăng ký, xác nhận mã, đăng nhập thiết bị nhớ và thiết bị mới, hồ sơ, đặt lịch, trùng lịch, huỷ, đặt lại, xoá hồ sơ); tiếp tân (đăng nhập, đổi email, đổi mật khẩu, hồ sơ, đặt hộ, đổi giờ, huỷ, check-in, hoàn tác, mời vào khám, hàng đợi, tự động mời, phiếu kết quả); người dùng đặt rồi tiếp tân khám rồi người dùng xem kết quả và lịch sử; admin (tài khoản, khoá, vai trò, đặt lại mật khẩu, khoá hồ sơ, bài viết, ghim, tải và lấy ảnh); quên mật khẩu qua email; dữ liệu demo có sẵn; ID hợp lệ về dạng nhưng không tồn tại và ID sai dạng.

Kết quả lần chạy cuối: `Đã chạy 304 bước, 0 bước khác nhau (0 điểm khác).` Trong đó có 153+ bước thành công (200/201) và phần còn lại là các lỗi thường gặp (VALIDATION, NOT_FOUND, CONFLICT, 401, 403, 429...). So từng mã HTTP, Content-Type, Cache-Control, Set-Cookie, và toàn bộ JSON.

### Quy tắc bỏ qua (mỗi quy tắc có ghi trong đầu `compare-responses.mjs`)

1. ID (`id`, `...Id`, `...Ids`): chỉ kiểm là chuỗi. ID Mongo 24 ký tự hex, ID MySQL là số (spec đã chốt, dạng JSON luôn là chuỗi).
2. `ticketCode`, `patientCode`, `devCode`: so khuôn (chữ số đổi thành 9). `resetToken`: chỉ kiểm là chuỗi. Đường dẫn ảnh `/api/uploads/<id>`: che id.
3. Thời điểm ISO có 3 chữ số mili giây và `Z`: chỉ kiểm đúng định dạng. `dob` so nguyên văn, trừ ở bước `mode: 'shape'`.
4. `retryAfter`, `waitedMinutes`...: chỉ kiểm là số (phụ thuộc giờ chạy).
5. Bước `mode: 'shape'` (danh sách lấy từ dữ liệu demo: lịch trong ngày, hàng đợi, bảng điều khiển, danh sách tài khoản và hồ sơ): chỉ so khoá, kiểu giá trị và mảng rỗng hay không.
6. Khung giờ trống của những ngày có phiếu demo (`booked`, `remaining`): bỏ hai khoá này; ngày xa không có phiếu demo vẫn so đủ.
7. Set-Cookie: so tên, HttpOnly, SameSite, Path, Secure; cookie bị xoá thì không so Max-Age và Expires (Node chỉ gửi Expires, Java gửi thêm Max-Age=0, trình duyệt xử lý như nhau). Cookie `ct_staff` bị bỏ qua (xem mục 3).
8. Header chỉ so Content-Type (loại, bỏ charset), Cache-Control; mục `[header]` so header bảo mật và CORS.

## 3. Khác biệt tìm thấy

| # | Khác biệt | Xử lý |
|---|---|---|
| 1 | Preflight CORS (`OPTIONS`) của Java chỉ có `Vary: Origin` khi yêu cầu không gửi `Access-Control-Request-Headers` (và với origin lạ); Node luôn thêm `Vary: Access-Control-Request-Headers` | Đã sửa trong `CorsHeadersFilter`; test `[java] preflight luôn có Vary: Access-Control-Request-Headers...` trong `ContractTest` |
| 2 | Đăng xuất: Node xoá cả `ct_session` và `ct_staff`, Java chỉ xoá `ct_session` | Không sửa, theo spec (bỏ cookie cũ `ct_staff`, đã ghi trong spec là quyết định chưa duyệt riêng) |
| 3 | `Content-Type` của JSON: Node `application/json; charset=utf-8`, Java `application/json` | Không sửa. JSON mặc định UTF-8 (RFC 8259), `fetch().json()` của trình duyệt không phụ thuộc charset; đổi toàn cục có nguy cơ vỡ các test hợp đồng đang xanh mà không có lợi ích |
| 4 | Node trả `ETag` yếu cho GET JSON, Java không | Không sửa. Chỉ ảnh hưởng băng thông (trình duyệt không nhận 304); API không phụ thuộc |
| 5 | Dữ liệu demo khác nhau: Node dùng `state * 1103515245` bằng số thực JavaScript, vượt 2^53 nên làm tròn, còn Java dùng số nguyên chính xác; ra họ tên, ngày sinh, địa chỉ, ghi chú khám khác nhau | Không sửa, đã chủ định (Java chính xác). Chỉ so cấu trúc và kiểu ở các bước dùng dữ liệu demo |
| 6 | Body JSON quá 1 MB và ảnh quá 3 MB: cả hai trả 500 `SERVER_ERROR` | Không phải khác biệt: Java giữ đúng hành vi của Node (Node không xử lý lỗi 413 riêng) |

Các khác biệt tìm thấy lúc đầu mà hoá ra do kịch bản (ID số so với ObjectId, khung giờ không tồn tại, số lần chạy lặp) đã được sửa trong script, không phải trong Java. Ví dụ: Node trả `NOT_FOUND` "Không tìm thấy dữ liệu." cho ID sai dạng (`CastError`), còn Java cũng trả đúng thông điệp đó; với ID đúng dạng nhưng không có thì cả hai trả thông điệp riêng từng loại.

## 4. Thử tay (03/11/2026)

Bản Java chạy profile demo ở `127.0.0.1:4100`, frontend (Vite) ở `127.0.0.1:5180`, thao tác trên Chrome.

- Bước 5, với frontend, SMS và email ở chế độ `console`: đăng ký kèm mã 6 số, đặt lịch (khung đã đủ chỗ bị khoá đúng), huỷ, tiếp tân check-in, mời người đầu hàng chờ vào ghế trống, lưu kết quả khám, Admin đăng bài kèm ảnh thu nhỏ (tải ảnh qua `/api/admin/uploads`), bài hiện ở trang tin tức. Đều đạt, không có lỗi trên console trình duyệt hay ERROR trong log server.
- Bước 6, Firebase thật (`SMS_PROVIDER=firebase`, `FIREBASE_TEST_NUMBERS_ONLY=1`): đăng ký bằng số thử trên Firebase Console, nhập mã thử, server kiểm ID token thật của Google, tạo tài khoản thành công.
- Bước 6, SMTP Gmail thật: quên mật khẩu tài khoản nội bộ, email chứa mã 6 số đến được hộp thư, nhập mã, đặt mật khẩu mới, đăng nhập lại bằng mật khẩu mới thành công.
  - Lần thử đầu thất bại vì `MAIL_FROM` chỉ có tên hiển thị, không có địa chỉ (`Local address contains control or whitespace`). Bản Java cần `MAIL_FROM` là địa chỉ, hoặc dạng `Tên <địa chỉ>`. Sửa `.env` xong thì gửi được.
