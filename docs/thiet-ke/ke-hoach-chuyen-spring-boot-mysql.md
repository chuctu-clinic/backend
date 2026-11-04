# Chuyển backend sang Spring Boot và MySQL: kế hoạch thực hiện

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Viết lại toàn bộ 77 endpoint của backend Node.js/MongoDB thành Spring Boot + MySQL trong `D:\chuctu-clinic\backend-java`, frontend chạy được mà gần như không phải sửa.

**Architecture:** Một ứng dụng Spring Boot chia gói theo chức năng (`auth`, `patient`, `appointment`, `queue`, `record`, `post`, `account`). Dùng JPA cho đọc ghi thường và SQL viết tay cho thao tác đồng thời. Code Node trong `backend/` là nguồn sự thật cho mọi thông báo, mã lỗi và dạng JSON.

**Tech Stack:** Java 21, Spring Boot (bản ổn định mới nhất khi tạo project), Maven Wrapper, Spring Web, Spring Security, Spring Data JPA, Flyway, Spring Mail, Nimbus JOSE + JWT, MySQL 8.4, JUnit 5, spring-security-crypto.

**Spec:** `backend/docs/superpowers/specs/2026-11-02-chuyen-spring-boot-mysql-design.md`

## Global Constraints

- Mọi đường dẫn nằm dưới `/api`, cổng 4000. Giữ nguyên phương thức, mã HTTP, mã lỗi, thông báo tiếng Việt.
- Lỗi: `{"error":{"code","message",...details}}`. Kiểm tra dữ liệu: mã `VALIDATION`, thông báo `Thông tin chưa hợp lệ, cô chú kiểm tra lại các ô được đánh dấu.`, `fields` lấy lỗi đầu tiên của mỗi ô, khoá lồng dùng dấu chấm, lỗi gốc dùng khoá `_`.
- ID trong JSON luôn là chuỗi. Thời điểm luôn có 3 chữ số mili giây và hậu tố `Z`.
- `dob` trả `YYYY-MM-DDT00:00:00.000Z`. `slotDate`, `visitDate`, `followUpDate` trả `YYYY-MM-DD`. Giờ nghiệp vụ theo `Asia/Ho_Chi_Minh`.
- Database `utf8mb4`, collation `utf8mb4_0900_as_ci`. Thời điểm là `DATETIME(3)` UTC. Ngày là `DATE`.
- Không dùng H2. Test chạy trên MySQL thật, database `chuctu_test`.
- Test không bao giờ gửi email hay SMS thật.
- Không đưa mật khẩu vào chat, không đọc nội dung `.env` thật. Chỉ đọc `.env.example`.
- Không sửa `backend/` ngoài thư mục `docs/`. Bản Node là mốc đối chiếu, tính cả các thay đổi chưa commit trong working tree.
- Không thêm tính năng. Khi tài liệu này im lặng, làm đúng như `backend/src`.
- Mỗi test Java có `@DisplayName` đúng bằng tên `it(...)` trong test Node tương ứng. Test mới không có bản Node thì đặt tiền tố `[java] `.

## Review Focus

1. Body đúng cú pháp JSON nhưng sai kiểu (`{"phone":123}`, `{"slotDate":null}`, body là mảng): phải trả 400 `VALIDATION`, không phải `BAD_JSON` hay 500. Test ở Task 2.
2. ID số quá lớn trong URL (`/api/me/appointments/99999999999999999999`): trả 404, không phải 500. Test ở Task 2.
3. Từ khoá tìm kiếm có `%`, `_`, `\`: coi là ký tự thường. Tìm `NGUYỄN` ra `Nguyễn`, tìm `Nguyen` không ra `Nguyễn`. Test ở Task 6.
4. Máy chủ chạy ở múi giờ khác (`-Duser.timezone=America/New_York`) hoặc lúc 00:30 giờ Việt Nam: ngày khám, ngày sinh không lệch. Test ở Task 1 và Task 7.
5. Huỷ phiếu trùng lúc với tác vụ tự huỷ hoặc với check-in: chỗ trong khung giờ chỉ được nhả một lần. Test ở Task 7.

---

## Cấu trúc file

```
backend-java/
  pom.xml, mvnw, mvnw.cmd, .gitignore, .env.example, README.md, Dockerfile, docker-compose.yml
  scripts/check-test-parity.mjs
  src/main/resources/application.yml
  src/main/resources/db/migration/V1__schema.sql
  src/main/java/vn/chuctu/clinic/
    ClinicApplication.java
    common/   AppProperties, Rules, Constants, BusinessClock, VnTime, Phones, Hmac, ApiException, Errors,
              GlobalExceptionHandler, Body, Opt, JacksonConfig, Counters, Paging, Likes
    security/ SessionService, SessionFilter, CurrentUser, SecurityConfig, BodyLimitFilter
    auth/     AuthController, AuthService, OtpService, VerificationCode(+Repository),
              TrustedDevice(+Repository), SmsGateway, EmailGateway, MailSender, FirebaseVerifier, NimbusFirebaseVerifier
    account/  User(+Repository), AccountService, AdminAccountController, StaffAccountController, MeController
    patient/  Patient(+Repository), PatientService, MePatientController, StaffPatientController, AdminPatientController
    appointment/ Appointment(+Repository), SlotService, AppointmentService, MeAppointmentController,
              StaffAppointmentController, NoShowJob
    queue/    Checkin(+Repository), QueueService, QueueController, Settings
    record/   MedicalRecord(+Repository), MedicalRecordEdit, RecordService, StaffRecordController
    post/     Post(+Repository), PostService, Upload(+Repository), UploadService, AdminPostController, PublicController
    dashboard/ DashboardService, AdminDashboardController
    seed/     Seeder, DemoClinic, DemoDatabase
  src/test/java/vn/chuctu/clinic/
    IntegrationTest.java, TestClient.java, FakeMail.java, FakeFirebase.java
    <mỗi file test Node một lớp *Test.java>
```

## Bảng đối chiếu test

| Test Node | Số test | Lớp Java | Task |
|---|---:|---|---:|
| `phone.test.js` | 8 | `PhonesTest` | 1 |
| `error-handler.test.js` | 5 | `ErrorHandlerTest` | 2 |
| `auth.test.js` | 28 | `AuthTest` | 3 |
| `forgot-password.test.js` | 11 | `ForgotPasswordTest` | 4 |
| `account-email.test.js` | 5 | `AccountEmailTest` | 4 |
| `email-smtp.test.js` | 2 | `EmailSmtpTest` | 4 |
| `firebase.test.js` | 4 | `FirebaseTest` | 5 |
| `profiles.test.js` | 32 | `ProfilesTest` | 6 và 7 |
| `staff-patient.test.js` | 3 | `StaffPatientTest` | 6 |
| `booking.test.js` | 13 | `BookingTest` | 7 |
| `slot-rules.test.js` | 4 | `SlotRulesTest` | 7 |
| `user-appointments.test.js` | 9 | `UserAppointmentsTest` | 7 |
| `queue.test.js` | 19 | `QueueTest` | 8 |
| `medical-records.test.js` | 9 | `MedicalRecordsTest` | 9 |
| `posts.test.js` | 22 | `PostsTest` | 10 |
| `admin-accounts.test.js` | 18 | `AdminAccountsTest` | 11 |
| `dashboard.test.js` | 4 | `DashboardTest` | 11 |

Tổng: 196. Bản Node chạy theo thứ tự trong file. Test Java phải chuyển đúng ý từng assert, không gộp hay bỏ.

**Cách chuyển một lớp test:** đọc file Node, viết các test Java cùng tên và cùng assert qua `TestClient`, chạy để thấy test đỏ, viết code, chạy tới khi xanh. Các Task 3 đến 11 đều theo chu trình này. Bước đầu của mỗi task chỉ ghi những test cần thêm ngoài bản Node.

---

### Task 1: Dựng project, cấu hình, cấu trúc bảng

**Files:**
- Create: `pom.xml`, `mvnw*`, `.gitignore`, `.env.example`, `application.yml`, `V1__schema.sql`, `ClinicApplication.java`
- Create: `common/AppProperties.java`, `Rules.java`, `Constants.java`, `BusinessClock.java`, `VnTime.java`, `Phones.java`, `Hmac.java`, `Counters.java`
- Test: `IntegrationTest.java`, `SchemaTest.java`, `VnTimeTest.java`, `PhonesTest.java`, `CountersTest.java`

**Interfaces:**
- Produces:
  - `AppProperties` đọc mọi biến của `config.js`, kiểm tra khi khởi động y như `config.js`. Thêm `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
  - `Rules`: hằng số từ `rules` trong `config.js`.
  - `Constants`: `ROLES`, `STAFF_ROLES`, `GENDERS`, `RELATIONSHIPS`, `SERVICE_TYPES`, `APPOINTMENT_STATUSES`, `ACTIVE_STATUSES`, `RECEIVING_HOURS`, `STATUS_LABELS`.
  - `BusinessClock { Instant now(); }`: tương đương `clock.now`, chỉ dùng ở 4 chỗ Node dùng `clock.now` (`autoCancelNoShows`, `isReceivingTime`, `assertReceivingTime`, `checkIn`). Nơi khác dùng bean `java.time.Clock` (`Clock.tickMillis(UTC)`).
  - `VnTime`: `LocalDate today(Instant)`, `int minutesOfDay(Instant)`, `Instant slotInstant(LocalDate, String hhmm)`, `int hhmmToMinutes(String)`, `String ddmm(LocalDate)`, `String yy(LocalDate)`.
  - `Phones`: `String normalize(String)`, `String problem(String raw, boolean required)`.
  - `Hmac`: `String sha256Hex(String secret, String value)`.
  - `Counters`: `long next(String key)`.
  - `IntegrationTest`: lớp cha `@SpringBootTest`, cổng ngẫu nhiên, xoá dữ liệu mọi bảng trừ `flyway_schema_history` rồi seed trước mỗi test. `BusinessClock` trong test là bản đổi được, mặc định 10:00 hôm nay theo giờ Việt Nam như `DEFAULT_NOW`.

- [ ] **Step 1: Người dùng tạo tài khoản MySQL.** Yêu cầu người dùng tự chạy (`mysql -u root -p`) với mật khẩu tự chọn, rồi điền `backend-java/.env`. Dừng lại chờ xác nhận.

```sql
CREATE USER 'chuctu'@'localhost' IDENTIFIED BY '<mật khẩu tự chọn>';
CREATE DATABASE chuctu CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;
CREATE DATABASE chuctu_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;
GRANT ALL ON chuctu.* TO 'chuctu'@'localhost';
GRANT ALL ON chuctu_test.* TO 'chuctu'@'localhost';
GRANT ALL ON chuctu_demo.* TO 'chuctu'@'localhost';
```

- [ ] **Step 2: Tạo project.** Tạo `D:\chuctu-clinic\backend-java`, `git init`, thêm Maven Wrapper, `.gitignore` có `.env` và `target/`. Đọc `.env` bằng `spring.config.import: optional:file:.env[.properties]`. Hibernate `ddl-auto: validate`, `jdbc.time_zone: UTC`. `.env.example` lấy từ bản Node, đổi `MONGODB_URI` thành ba biến `DB_*`.

- [ ] **Step 3: Viết test đỏ.**

```java
// SchemaTest
@Test void chan_hai_nguoi_cung_mot_ghe()      // hai checkin IN_ROOM cùng visit_date + seat_no -> DuplicateKeyException
@Test void cho_phep_ghe_trung_khi_khong_trong_phong() // WAITING seat_no NULL nhiều dòng -> không lỗi
@Test void chan_hai_checkin_hieu_luc_cung_phieu()     // REVERTED + WAITING cùng phiếu: được; WAITING + COMPLETED: lỗi
@Test void chi_mot_bai_duoc_ghim()
@Test void nhieu_tai_khoan_khong_co_email()
@Test void email_khong_phan_biet_hoa_thuong()         // A@x.vn và a@x.vn -> DuplicateKeyException
// VnTimeTest
assertEquals(LocalDate.parse("2026-11-02"), VnTime.today(Instant.parse("2026-11-01T17:30:00Z")));
assertEquals(Instant.parse("2026-11-02T01:00:00Z"), VnTime.slotInstant(LocalDate.parse("2026-11-02"), "08:00"));
assertEquals("0211", VnTime.ddmm(LocalDate.parse("2026-11-02")));
// CountersTest
@DisplayName("[java] 20 luồng lấy số không trùng") // 20 luồng next("x") ra đủ 1..20
```

Thêm `PhonesTest` chuyển từ `phone.test.js`.

- [ ] **Step 4: Chạy `./mvnw -q test`.** Mong đợi: lỗi biên dịch hoặc test đỏ.

- [ ] **Step 5: Viết `V1__schema.sql` và các lớp `common`.** Tạo đủ 13 bảng trong bảng ở §4 của spec, cột theo các model Mongoose, khoá ngoại, chỉ mục tương đương, cột sinh tự động ở §4 "Ràng buộc có điều kiện". `Counters.next` dùng đúng SQL trong spec trên một kết nối.

- [ ] **Step 6: Chạy lại với múi giờ JVM khác.** Chạy `./mvnw -q test` rồi `./mvnw -q test -DargLine=-Duser.timezone=America/New_York`. Mong đợi: cả hai xanh.

- [ ] **Step 7: Commit.** `feat: dựng project, cấu hình và cấu trúc bảng`

---

### Task 2: Lỗi, JSON, đọc dữ liệu, phiên và phân quyền

**Files:**
- Create: `common/ApiException.java`, `Errors.java`, `GlobalExceptionHandler.java`, `Body.java`, `Opt.java`, `JacksonConfig.java`, `Paging.java`, `Likes.java`
- Create: `security/SessionService.java`, `SessionFilter.java`, `CurrentUser.java`, `SecurityConfig.java`, `BodyLimitFilter.java`, `account/User.java`, `account/UserRepository.java`
- Test: `ErrorHandlerTest.java`, `ContractTest.java`, `SessionTest.java`, `TestClient.java`

**Interfaces:**
- Consumes: `AppProperties`, `Rules`.
- Produces:
  - `Errors`: `badRequest(msg)`, `badRequest(msg, details, code)`, `unauthorized()`, `forbidden()`, `forbidden(msg)`, `notFound()`, `notFound(msg)`, `conflict(msg, code)`, `conflict(msg, code, details)`, `tooMany(msg, details)`. Tất cả trả `ApiException`, mặc định khớp `utils/errors.js`.
  - `Opt<T>`: ba trạng thái `isAbsent()`, `isPresent()`, `get()` (có thể null). Thay cho `undefined`.
  - `Body`: đọc `JsonNode`, thu lỗi theo từng ô, tương đương zod. `Body.of(JsonNode)`, `String requiredString(field, rule)`, `Opt<String> optionalText(field, max)`, `Opt<LocalDate> date(field)`, `Opt<Long> id(field)`, `void check()` (ném `VALIDATION` nếu có lỗi). Body không phải object cũng trả `VALIDATION`. Trường lạ bị bỏ qua.
  - `Paging.of(page, limit, defaultLimit)` trả `page`, `limit`, `offset`, giống `paging()`.
  - `Likes.contains(String)`: escape `\`, `%`, `_` rồi bọc `%`.
  - `SessionService`: `void setSession(HttpServletResponse, User, boolean remember)`, `void clearSession(HttpServletResponse)`, `void setDeviceCookie(HttpServletResponse, String)`.
  - `CurrentUser`: `User get()`, `User require()`. Quyền theo URL: `/api/me/**` USER, `/api/staff/**` RECEPTIONIST + ADMIN, `/api/admin/**` ADMIN, chặn `mustChangePassword` như `requireRole`.
  - `TestClient`: `post(path, json)`, `get(path)`, `put`, `patch`, `delete`, giữ cookie như `request.agent`, kết quả có `status()` và `json()`. `registerUser(phone, name)`, `staff(identifier)`, `selfPatientId()` theo `helpers.js`.

- [ ] **Step 1: Viết test đỏ.** Chuyển `ErrorHandlerTest`. Thêm:

```java
// ContractTest (tiền tố [java])
body_sai_kieu_tra_VALIDATION()   // POST /api/auth/login {"phone":123} -> 400 VALIDATION, có fields.phone
body_la_mang_tra_VALIDATION()    // POST /api/auth/login [] -> 400 VALIDATION
id_qua_lon_tra_404()             // GET /api/me/appointments/99999999999999999999 -> 404 NOT_FOUND
thoi_diem_co_3_so_mili_giay()    // JSON khớp \d{4}-..T..:..:..\.\d{3}Z, kể cả giá trị tròn giây
json_qua_1mb_tra_loi()           // body hơn 1 MB -> đúng mã HTTP và body mà bản Node trả (lấy ở Step 1)
// SessionTest (tiền tố [java])
jwt_sub_khong_phai_so_bi_xoa_cookie() // token HS256 hợp lệ, sub 24 ký tự hex -> coi là chưa đăng nhập, Set-Cookie xoá ct_session
jwt_sai_chu_ky_bi_xoa_cookie()
tai_khoan_bi_khoa_mat_phien_ngay()
lech_x_session_user_tra_409()    // SESSION_CHANGED, /api/auth/me vẫn qua
khong_chon_giu_dang_nhap_khong_co_max_age()
production_cookie_samesite_none_secure()
```

Trước khi viết `json_qua_1mb_tra_loi`, chạy thử bản Node để lấy response thật, ghi đúng vào test.

- [ ] **Step 2: Chạy `./mvnw -q test -Dtest='ErrorHandlerTest,ContractTest,SessionTest'`.** Mong đợi: đỏ.

- [ ] **Step 3: Viết code.** `@ControllerAdvice` xử lý như `errorHandler`: `DuplicateKeyException` và `DataIntegrityViolationException` thành 409 `DUPLICATE`, `MethodArgumentTypeMismatchException` thành 404. Controller nhận id là `String`, hoặc `long` để lỗi đổi kiểu thành 404. Lỗi 404 đường dẫn lạ trả `Không tìm thấy đường dẫn.`. Các header tương đương helmet đặt trong `SecurityConfig`. CORS theo `CLIENT_ORIGIN`. Tin một lớp proxy qua `server.forward-headers-strategy: framework`.

- [ ] **Step 4: Chạy lại test ở Step 2.** Mong đợi: xanh.

- [ ] **Step 5: Commit.** `feat: hợp đồng lỗi, JSON, phiên đăng nhập và phân quyền`

---

### Task 3: Đăng ký, đăng nhập, OTP tin nhắn

**Files:**
- Create: `auth/AuthController.java`, `AuthService.java`, `OtpService.java`, `VerificationCode.java`, `VerificationCodeRepository.java`, `TrustedDevice.java`, `TrustedDeviceRepository.java`, `SmsGateway.java`
- Create: `patient/Patient.java`, `PatientRepository.java`, `PatientService.java` (chỉ `createPatient`)
- Test: `AuthTest.java`

**Interfaces:**
- Consumes: `Hmac`, `Counters`, `Phones`, `SessionService`, `Body`, `Errors`.
- Produces:
  - `OtpService`: `Map<String,Object> issue(String phone, String purpose, JsonNode registerData, String email)`, `VerificationCode verify(String phone, String purpose, String code)`, `VerificationCode verifyProof(String phone, String purpose, Proof proof)`.
  - `record Proof(String code, String idToken)`.
  - `AuthService`: các hàm cùng tên `auth.service.js`: `startRegister`, `completeRegister`, `startLogin`, `completeLogin`, `resendCode`, `staffLogin`, `changePassword`, `hashPassword`.
  - `PatientService.createPatient(PatientInput, Long userId, String relationship, Long createdBy)`, `record PatientInput(String fullName, LocalDate dob, Integer birthYear, String gender, String phone, String address, String nationalId, String email)`.
  - `FirebaseVerifier { FirebaseInfo verify(String idToken); }`, `record FirebaseInfo(String phone, Instant authTime)`, bản tạm ném lỗi cho tới Task 5.

- [ ] **Step 1: Chuyển `AuthTest` từ `auth.test.js`.** Test Node đọc thẳng `VerificationCode` để chỉnh `createdAt`, dùng repository tương ứng.
- [ ] **Step 2: Chạy `./mvnw -q test -Dtest=AuthTest`.** Mong đợi: đỏ.
- [ ] **Step 3: Viết code.** Công thức HMAC giữ đúng `hmac('sha256', JWT_SECRET)`: mã là `${phone}:${purpose}:${code}`, thiết bị là `device:${deviceId}`. `newDeviceId` là 32 byte ngẫu nhiên base64url. `/api/auth/*` cho qua kiểm tra `x-session-user`. Mã sai tăng `attempts` phải được commit dù request trả lỗi: không rollback phần đó.
- [ ] **Step 4: Chạy lại `AuthTest`.** Mong đợi: 28 test xanh.
- [ ] **Step 5: Commit.** `feat: đăng ký, đăng nhập và mã xác nhận`

---

### Task 4: Email, quên mật khẩu, đổi email

**Files:**
- Create: `auth/EmailGateway.java`, `auth/MailSender.java`, `account/StaffAccountController.java`
- Modify: `auth/AuthService.java`, `auth/AuthController.java`
- Test: `ForgotPasswordTest.java`, `AccountEmailTest.java`, `EmailSmtpTest.java`, `FakeMail.java`

**Interfaces:**
- Consumes: `OtpService`, `AuthService`.
- Produces:
  - `MailSender { void send(String to, String from, String subject, String text); }`, bản thật dùng `JavaMailSender`, cổng 465 bật SSL trực tiếp, bỏ dấu cách trong `SMTP_PASS`.
  - `EmailGateway` theo `emailGateway.js`: cùng tiêu đề, nội dung, lỗi 502 `EMAIL_FAILED`.
  - `FakeMail implements MailSender`: ghi lại thư, bật được chế độ lỗi.
  - `AuthService`: `startPasswordReset`, `verifyPasswordReset`, `resetPassword`, `startEmailChange`, `confirmEmailChange`.

- [ ] **Step 1: Chuyển ba lớp test.** `EmailSmtpTest` thay `smtpTransport.create` bằng `FakeMail`, đổi provider qua `@TestPropertySource`.
- [ ] **Step 2: Chạy `./mvnw -q test -Dtest='ForgotPasswordTest,AccountEmailTest,EmailSmtpTest'`.** Mong đợi: đỏ.
- [ ] **Step 3: Viết code.** `resetKey` là `hmac('reset-password')`. `passwordStamp` là 24 ký tự đầu của `hmac('pw:' + passwordHash)`. Token hết hạn sau 10 phút. Staff account: `PATCH /account`, `POST /account/email`, `POST /account/email/verify`.
- [ ] **Step 4: Chạy lại.** Mong đợi: 18 test xanh.
- [ ] **Step 5: Commit.** `feat: gửi mã email, quên mật khẩu và đổi email`

---

### Task 5: Kiểm tra token Firebase

**Files:**
- Create: `auth/NimbusFirebaseVerifier.java`, `src/test/.../FakeFirebase.java`
- Modify: `auth/AuthService.java` (`verifyFirebase`)
- Test: `FirebaseTest.java`, `NimbusFirebaseVerifierTest.java`

**Interfaces:**
- Consumes: `FirebaseVerifier`, `FirebaseInfo`, `AppProperties`.
- Produces: `NimbusFirebaseVerifier(String projectId, JWKSource<SecurityContext> keys, boolean allowEmulator, Clock clock)`.

- [ ] **Step 1: Chuyển `FirebaseTest`, viết thêm test đỏ với khoá RSA sinh trong test.**

```java
// NimbusFirebaseVerifierTest (tiền tố [java])
token_dung_duoc_nhan()            // RS256, iss/aud đúng -> phone và authTime đúng
sai_chu_ky_bi_tu_choi()           // ký bằng khoá khác
sai_iss_bi_tu_choi()
sai_aud_bi_tu_choi()
het_han_bi_tu_choi()
auth_time_tuong_lai_bi_tu_choi()
sub_rong_bi_tu_choi()
token_khong_ky_bi_tu_choi_khi_khong_phai_emulator() // alg=none
token_khong_ky_bi_tu_choi_khi_production()          // allowEmulator=false dù có FIREBASE_AUTH_EMULATOR_HOST
token_khong_ky_duoc_nhan_khi_emulator()
```

- [ ] **Step 2: Chạy `./mvnw -q test -Dtest='FirebaseTest,NimbusFirebaseVerifierTest'`.** Mong đợi: đỏ.
- [ ] **Step 3: Viết code theo §4 "Firebase" của spec.** `allowEmulator = emulatorHost có giá trị && !isProd`. JWKS dùng `RemoteJWKSet` có cache. `/api/meta` trả `firebaseWebConfig` được làm ở Task 10.
- [ ] **Step 4: Chạy lại.** Mong đợi: xanh.
- [ ] **Step 5: Commit.** `feat: kiểm tra ID token Firebase`

---

### Task 6: Hồ sơ bệnh nhân

**Files:**
- Modify: `patient/PatientService.java`
- Create: `patient/MePatientController.java`, `StaffPatientController.java`, `AdminPatientController.java`, `account/MeController.java`, `account/AccountService.java` (`updateMe`, đồng bộ hồ sơ)
- Test: `ProfilesTest.java`, `StaffPatientTest.java`, `SearchTest.java`

**Interfaces:**
- Consumes: `Body`, `Opt`, `Counters`, `Likes`, `CurrentUser`.
- Produces:
  - `PatientService`: `assertFresh(Instant current, String expected)`, `syncSelfProfile(User)`, `setPatientLock(long id, boolean locked)`, `Map<String,Object> toPublic(Patient)`, cùng các hàm còn lại của `patient.service.js`.
  - Mã bệnh nhân `CT-BN-YY-0001`, khoá `patient-YY`.

- [ ] **Step 1: Chuyển test.** Test trong `profiles.test.js` cần đặt lịch hoặc bệnh án thì để `@Disabled("Task 7")`, hoàn thành ở Task 7. Thêm:

```java
// SearchTest (tiền tố [java]), dữ liệu: "Nguyễn Văn A", "Tran_B", "100% Khoe"
tim_khong_phan_biet_hoa_thuong()  // q=NGUYỄN ra Nguyễn Văn A
tim_phan_biet_dau()               // q=Nguyen không ra Nguyễn Văn A
ky_tu_dac_biet_la_chu_thuong()    // q=% chỉ ra 100% Khoe; q=_ chỉ ra Tran_B; q=\ không lỗi
```

- [ ] **Step 2: Chạy `./mvnw -q test -Dtest='ProfilesTest,StaffPatientTest,SearchTest'`.** Mong đợi: đỏ.
- [ ] **Step 3: Viết code theo `patient.service.js`, `me.routes.js` và `staff.routes.js`.** `birthYear` trả theo `birthYear ?? năm của dob`. Hồ sơ đã có lịch sử không xoá cứng (BR.38). So sánh `assertFresh` theo mili giây.
- [ ] **Step 4: Chạy lại.** Mong đợi: xanh, các test `@Disabled` ghi rõ tên.
- [ ] **Step 5: Commit.** `feat: hồ sơ bệnh nhân và tài khoản người dùng`

---

### Task 7: Đặt lịch, khung giờ, tự huỷ

**Files:**
- Create: `appointment/Appointment.java`, `AppointmentRepository.java`, `SlotService.java`, `AppointmentService.java`, `MeAppointmentController.java`, `StaffAppointmentController.java`, `NoShowJob.java`
- Modify: `ProfilesTest.java` (bỏ `@Disabled`)
- Test: `BookingTest.java`, `SlotRulesTest.java`, `UserAppointmentsTest.java`, `BookingConcurrencyTest.java`

**Interfaces:**
- Consumes: `Counters`, `VnTime`, `BusinessClock`, `PatientService`.
- Produces:
  - `SlotService`: `boolean reserve(LocalDate, String start)`, `void release(LocalDate, String start)`, `String timeIssue(LocalDate, String start, String mode, Instant now)`, `List<Map<String,Object>> availability(LocalDate, String mode, Instant now)`.
  - `AppointmentService`: `userBook`, `counterBook`, `userCancel`, `counterCancel`, `reschedule`, `autoCancelNoShows(Instant now)`, `appointmentView`, `listUserAppointments`, `getUserAppointment`, `listDay`, `datesInMonth`, `findByTicketCode`, `getStaffAppointment`, `listPatientAppointments`.
  - Mã phiếu `CT-PK-YY-DDMM-001`, khoá `ticket-YYYY-MM-DD`.
  - `cancelInternal` gọi `QueueService.leaveQueue(long appointmentId)` ở Task 8. Trong task này đặt lệnh SQL `UPDATE checkins ... REVERTED, seat_no=NULL` trực tiếp là đủ.

- [ ] **Step 1: Chuyển ba lớp test, viết thêm test đỏ.**

```java
// BookingConcurrencyTest (tiền tố [java]), dùng luồng thật, không @Transactional
chin_nguoi_dat_sau_cho()      // đúng 6 thành công, 3 SLOT_FULL, slot_counters.count = 6, 6 phiếu trong DB
ma_phieu_khong_trung_khi_dat_dong_thoi()
huy_trung_voi_tu_huy_nha_cho_mot_lan()   // đặt 1 phiếu, count=1, gọi đồng thời counterCancel + autoCancelNoShows -> count=0, không âm
huy_hai_lan_dong_thoi_nha_cho_mot_lan()  // đặt 2 phiếu cùng khung, huỷ phiếu 1 từ 2 luồng -> count=1
doi_gio_khung_day_giu_nguyen_phieu_cu()  // khung mới đủ 6 -> SLOT_FULL, phiếu cũ vẫn BOOKED, count khung cũ không đổi
// BookingTest bổ sung
lich_gan_nua_dem_khong_lech_ngay()       // BusinessClock 00:30 giờ VN: slotDate trả đúng ngày
```

- [ ] **Step 2: Chạy `./mvnw -q test -Dtest='BookingTest,SlotRulesTest,UserAppointmentsTest,BookingConcurrencyTest'`.** Mong đợi: đỏ.
- [ ] **Step 3: Viết code.**
  - `reserve` dùng hai câu trong spec, chạy trong transaction của thao tác.
  - `createAppointment`, `reschedule`, `cancelInternal` dùng `@Transactional`.
  - `cancelInternal` và `autoCancelNoShows` chỉ gọi `release` nếu `UPDATE ... WHERE status IN (...)` đổi đúng 1 dòng.
  - Đặt lịch thử lại khi deadlock (MySQL 1213), tối đa 3 lần, ở lớp ngoài transaction.
  - `NoShowJob` chạy khi `ApplicationReadyEvent` và `@Scheduled(fixedDelay = 60000)`, không chạy trong profile test, bắt lỗi rồi ghi log như `index.js`.
- [ ] **Step 4: Chạy lại, thêm `ProfilesTest`.** Mong đợi: xanh, không còn `@Disabled`.
- [ ] **Step 5: Chạy `BookingConcurrencyTest` 5 lần liên tiếp.** Mong đợi: cả 5 lần xanh.
- [ ] **Step 6: Commit.** `feat: đặt lịch, khung giờ và tự huỷ phiếu`

---

### Task 8: Hàng đợi

**Files:**
- Create: `queue/Checkin.java`, `CheckinRepository.java`, `QueueService.java`, `QueueController.java`, `Settings.java`
- Modify: `appointment/AppointmentService.java`
- Test: `QueueTest.java`, `QueueConcurrencyTest.java`

**Interfaces:**
- Consumes: `AppointmentService`, `BusinessClock`.
- Produces:
  - `Settings`: `<T> T get(String key, T fallback, Class<T> type)`, `void set(String key, Object value)`.
  - `QueueService`: `priorityGroupFor`, `checkIn`, `undoCheckIn`, `inviteNext`, `enterRoom`, `undoEnterByAppointment`, `undoEnter`, `fillSeats`, `completeCheckin(long checkinId, Instant now)`, `queueState`, `checkinsByAppointment`, `leaveQueue`.

- [ ] **Step 1: Chuyển `QueueTest`, viết thêm test đỏ.**

```java
// QueueConcurrencyTest (tiền tố [java])
moi_dong_thoi_khong_trung_ghe() // 8 người chờ, 8 luồng inviteNext -> 5 thành công, 3 ROOM_FULL hoặc QUEUE_EMPTY, ghế 1..5 không trùng
checkin_hai_lan_dong_thoi()     // 2 luồng -> 1 thành công, 1 lần trả lỗi, 1 check-in hiệu lực
```

- [ ] **Step 2: Chạy `./mvnw -q test -Dtest='QueueTest,QueueConcurrencyTest'`.** Mong đợi: đỏ.
- [ ] **Step 3: Viết code.** `seatOne`: mỗi lần thử ghế là một transaction riêng (`REQUIRES_NEW`) dùng `JdbcTemplate`, bắt `DuplicateKeyException` rồi thử ghế tiếp, tối đa 5 lần. Thứ tự chờ: PRIORITY trước, trong nhóm theo `checkInTime`. Nối `AppointmentService.listDay` nếu Node có gắn thông tin check-in ở route. Đổi lệnh SQL tạm ở Task 7 thành `leaveQueue`.
- [ ] **Step 4: Chạy lại.** Mong đợi: xanh, 5 lần liên tiếp với `QueueConcurrencyTest`.
- [ ] **Step 5: Commit.** `feat: hàng đợi, check-in và mời vào khám`

---

### Task 9: Bệnh án

**Files:**
- Create: `record/MedicalRecord.java`, `MedicalRecordEdit.java`, `MedicalRecordRepository.java`, `RecordService.java`, `StaffRecordController.java`
- Modify: `appointment/MeAppointmentController.java` (`/appointments/:id/result`), `patient/MePatientController.java` (`/history`)
- Test: `MedicalRecordsTest.java`

**Interfaces:**
- Consumes: `QueueService.completeCheckin`, `PatientService`.
- Produces: `RecordService`: `recordView`, `createRecord`, `updateRecord`, và các hàm còn lại của `medicalRecord.service.js`.

- [ ] **Step 1: Chuyển `MedicalRecordsTest`.** Thêm `[java] người dùng không xem được kết quả của tài khoản khác`: A gọi `/api/me/appointments/<id của B>/result` phải trả 404. Thêm `[java] test ngày` kiểm `visitDate` và `followUpDate` dạng `YYYY-MM-DD`, `patient.dob` dạng `YYYY-MM-DDT00:00:00.000Z`.
- [ ] **Step 2: Chạy `./mvnw -q test -Dtest=MedicalRecordsTest`.** Mong đợi: đỏ.
- [ ] **Step 3: Viết code.** `createRecord` là một transaction, gồm lưu phiếu, đổi phiếu khám sang COMPLETED và gọi `completeCheckin`. `editableUntil = createdAt + 24h`. `changes` lưu JSON dạng `{field:{from,to}}`. `editHistory` đọc theo thứ tự lưu.
- [ ] **Step 4: Chạy lại.** Mong đợi: xanh.
- [ ] **Step 5: Commit.** `feat: phiếu kết quả khám`

---

### Task 10: Bài viết, ảnh, API công khai

**Files:**
- Create: `post/Post.java`, `PostRepository.java`, `PostService.java`, `Upload.java`, `UploadRepository.java`, `UploadService.java`, `AdminPostController.java`, `PublicController.java`
- Test: `PostsTest.java`, `UploadTest.java`, `MetaTest.java`

**Interfaces:**
- Consumes: `Body`, `Paging`, `Likes`, `SlotService` (nếu `/meta` cần), `CurrentUser`.
- Produces: `PostService`: `postView`, `listPublished`, `getPublished`, `adminList`, `adminGet`, `createPost`, `updatePost`, `setPinned`, `deletePost`. `UploadService`: `saveImage`, `getImage`.

- [ ] **Step 1: Chuyển `PostsTest`, viết thêm test đỏ.**

```java
// UploadTest (tiền tố [java])
anh_duoi_3mb_luu_va_doc_lai_dung_byte()   // Content-Type, Cache-Control: public, max-age=31536000, immutable
anh_tren_3mb_tra_loi_nhu_node()           // lấy response thật từ bản Node trước khi viết
sai_dinh_dang_tra_400()
thumbnail_chap_nhan_uploads_so()          // "/api/uploads/12" hợp lệ, "/api/uploads/abc" không
// MetaTest (tiền tố [java])
meta_giong_node()                         // so từng khoá với public.routes.js
```

- [ ] **Step 2: Chạy `./mvnw -q test -Dtest='PostsTest,UploadTest,MetaTest'`.** Mong đợi: đỏ.
- [ ] **Step 3: Viết code.** `getPublished`: toàn chữ số là ID, còn lại là slug. Sắp xếp theo `pinned` giảm dần phải đưa bài ghim lên đầu trong MySQL: `ORDER BY pinned IS NULL, published_at DESC`. `setPinned` không đổi `updated_at`: dùng SQL trực tiếp, trong một transaction. Upload nhận `byte[]`, giới hạn đúng 3 MB, ngoại lệ với giới hạn JSON 1 MB ở `BodyLimitFilter`.
- [ ] **Step 4: Chạy lại.** Mong đợi: xanh.
- [ ] **Step 5: Commit.** `feat: bài viết, ảnh và API công khai`

---

### Task 11: Quản lý tài khoản, dashboard

**Files:**
- Modify: `account/AccountService.java`
- Create: `account/AdminAccountController.java`, `dashboard/DashboardService.java`, `AdminDashboardController.java`
- Test: `AdminAccountsTest.java`, `DashboardTest.java`, `RouteParityTest.java`

**Interfaces:**
- Consumes: `AuthService.hashPassword`, `PatientService.syncSelfProfile`, `SessionFilter`.
- Produces: `AccountService`: `listAccounts`, `createAccount`, `getAccount`, `updateAccount`, `setAccountActive`, `setRole`, `resetPassword`. `DashboardService.overview()`.

- [ ] **Step 1: Chuyển hai lớp test, viết `RouteParityTest`.**

```java
// [java] đủ 77 endpoint: đọc RequestMappingHandlerMapping, so với danh sách 77 cặp phương thức + đường dẫn
// ghi cứng trong test (lấy từ các file backend/src/routes/*.routes.js, /api/health tính 1 endpoint).
```

- [ ] **Step 2: Chạy `./mvnw -q test -Dtest='AdminAccountsTest,DashboardTest,RouteParityTest'`.** Mong đợi: đỏ.
- [ ] **Step 3: Viết code theo `account.service.js`, `dashboard.service.js`, `admin.routes.js`.**
- [ ] **Step 4: Chạy toàn bộ `./mvnw -q test`.** Mong đợi: xanh.
- [ ] **Step 5: Commit.** `feat: quản lý tài khoản và dashboard`

---

### Task 12: Seed, chế độ demo, tác vụ dọn mã

**Files:**
- Create: `seed/Seeder.java`, `DemoClinic.java`, `DemoDatabase.java`, `auth/VerificationCleanupJob.java`
- Test: `SeedTest.java`, `DemoDatabaseTest.java`, `CleanupTest.java`

**Interfaces:**
- Consumes: mọi service trên.
- Produces: `Seeder.run(boolean demo)`. `DemoDatabase.prepare(String dbUrl)` chạy trước khi Flyway khởi động.

- [ ] **Step 1: Viết test đỏ.**

```java
seed_chay_hai_lan_khong_tao_trung()       // users, 4 trang thông tin
production_thieu_mat_khau_admin_bao_loi() // khớp seed.js
demo_chi_dung_den_chuctu_demo()           // DB_URL khác chuctu_demo thì DemoDatabase từ chối
demo_cam_khi_production()
don_ma_qua_han_mot_gio()                  // expires_at cũ hơn 1 giờ bị xoá, còn trong 1 giờ thì giữ
```

- [ ] **Step 2: Chạy `./mvnw -q test -Dtest='SeedTest,DemoDatabaseTest,CleanupTest'`.** Mong đợi: đỏ.
- [ ] **Step 3: Viết code.** Bật demo bằng profile `demo`: kết nối server với URL thay tên database thành `chuctu_demo`, `DROP DATABASE IF EXISTS`, `CREATE DATABASE`, rồi Flyway và seed. Dọn mã chạy mỗi 10 phút, mốc thời gian lấy từ `Clock`. Thêm lệnh seed: tham số `--seed` chạy xong thì thoát.
- [ ] **Step 4: Chạy lại, rồi `./mvnw -q spring-boot:run -Dspring-boot.run.profiles=demo`.** Mong đợi: test xanh, `GET http://127.0.0.1:4000/api/health` trả `{"ok":true}`.
- [ ] **Step 5: Commit.** `feat: seed, chế độ demo và dọn mã xác nhận`

---

### Task 13: Đối chiếu với bản Node và thử trên frontend

**Files:**
- Create: `scripts/check-test-parity.mjs`, `scripts/compare-responses.mjs`, `docs/ket-qua-doi-chieu.md`

**Interfaces:**
- Consumes: bản Node (`npm run dev:demo`, cổng tạm) và bản Java (profile demo).

- [ ] **Step 1: Viết `check-test-parity.mjs`.** Lấy tên `it(...)` từ `backend/test/*.test.js`, lấy `@DisplayName` từ `src/test`, in tên thiếu. Không tính tên có tiền tố `[java] `.
- [ ] **Step 2: Chạy `node scripts/check-test-parity.mjs`.** Mong đợi: `Đủ 196/196`.
- [ ] **Step 3: Viết `compare-responses.mjs`.** Chạy cùng kịch bản trên hai địa chỉ: đăng ký, đặt lịch, check-in, hàng đợi, bệnh án, bài viết, cả các lỗi thường gặp. So cấu trúc JSON, kiểu giá trị và giá trị, bỏ qua ID, mã phiếu, thời điểm. Dùng `127.0.0.1` để không ảnh hưởng cookie của người dùng trên `localhost`.
- [ ] **Step 4: Chạy và sửa mọi khác biệt ở bản Java.** Mong đợi: không còn khác biệt, hoặc khác biệt được ghi lý do trong `docs/ket-qua-doi-chieu.md` và báo người dùng.
- [ ] **Step 5: Thử tay với frontend.** Dùng skill `run`. Chạy bản Java ở cổng 4000, frontend ở cổng 5173, truy cập qua `127.0.0.1`. Thử: đăng ký, đặt lịch, huỷ, tiếp nhận, mời vào khám, lưu kết quả, quản lý bài viết, tải ảnh.
- [ ] **Step 6: Thử OTP email và Firebase thật.** Người dùng tự điền `backend-java/.env`. Thử quên mật khẩu qua SMTP, đăng nhập bằng số thử trên Firebase Console với `SMS_PROVIDER=firebase`. Nếu chưa điền được, ghi là chưa thử và báo rõ.
- [ ] **Step 7: Commit.** `test: đối chiếu với bản Node`

---

### Task 14: Docker và tài liệu

**Files:**
- Create: `Dockerfile`, `docker-compose.yml`, `.dockerignore`, `README.md`

- [ ] **Step 1: Viết `Dockerfile` nhiều tầng.** Tầng build dùng JDK 21, tầng chạy dùng JRE 21, không chạy bằng root.
- [ ] **Step 2: Viết `docker-compose.yml`.** Gồm `mysql:8.4` với healthcheck và volume, `server` ở cổng 4000, cùng tên biến với bản Node.
- [ ] **Step 3: Kiểm tra cú pháp nếu có thể.** Nếu máy có Docker, chạy `docker compose config` và `docker compose up --build`. Nếu không, ghi rõ trong README và báo cáo bàn giao rằng phần Docker chưa chạy thử.
- [ ] **Step 4: Viết `README.md`.** Chuyển nội dung README Node sang: chạy thử, biến cấu hình, đối chiếu BR, Firebase, Docker, yêu cầu MySQL 8.0 trở lên.
- [ ] **Step 5: Chạy `./mvnw -q verify`.** Mong đợi: xanh.
- [ ] **Step 6: Commit.** `docs: Docker và README`

**Không làm trong kế hoạch này:** xoá `backend/`, đổi `frontend/vercel.json`. Hai việc này chờ người dùng quyết định sau khi xem kết quả.
