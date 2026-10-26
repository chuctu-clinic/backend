import { fileURLToPath } from 'node:url';
import { Appointment, Patient, Post, SlotCounter, User, nextSeq } from './models/index.js';
import { seedClinicDemo } from './demoClinic.js';
import { hashPassword } from './services/auth.service.js';
import { createPatient } from './services/patient.service.js';
import { addDays, ddmm, slotDateValue, slotInstant, todayVN, yy } from './utils/time.js';

const DEFAULT_PASSWORD = 'ChucTu@2026';

const PAGES = [
  {
    slug: 'gioi-thieu',
    title: 'Về Phòng khám Chúc Từ',
    summary:
      'Phòng Chẩn Trị Y Học Cổ Truyền Từ Thiện Chúc Từ — khám bệnh, trị liệu và bốc thuốc hoàn toàn miễn phí cho người già neo đơn, người lao động nghèo và bà con có hoàn cảnh khó khăn. Phòng khám mở cửa tất cả các ngày trong tuần, kể cả Chủ Nhật, từ 8:00 đến 16:30.',
    content: `Phòng Chẩn Trị Y Học Cổ Truyền Từ Thiện Chúc Từ là cơ sở khám chữa bệnh bằng y học cổ truyền **miễn phí** cho người có hoàn cảnh khó khăn.

Phòng khám chính thức hoạt động từ ngày **08/11/2025** (tức ngày 19/09 Âm lịch).

## Chúng tôi làm gì

Phòng khám chuyên điều trị và phục hồi chức năng bằng phương pháp y học cổ truyền: châm cứu, bấm huyệt, xoa bóp và kê toa thuốc y học cổ truyền.

Bà con được thăm khám, điều trị các bệnh về cơ xương khớp, đau nhức, tai biến, mất ngủ, đau đầu… Toàn bộ quá trình từ khám, trị liệu đến bốc thuốc đều hoàn toàn miễn phí, nhờ tấm lòng y đức của các y bác sĩ và sự đóng góp của các Mạnh Thường Quân.

## Người phụ trách

Sư cô Chúc Từ — điện thoại 0397 116 666 hoặc 0835 555 115.`,
  },
  {
    slug: 'dich-vu',
    title: 'Dịch vụ khám và điều trị',
    summary:
      'Phòng khám chuyên điều trị và phục hồi chức năng bằng y học cổ truyền: bệnh cơ xương khớp, đau nhức, tai biến, mất ngủ, đau đầu. Bác sĩ xem mạch và chẩn đoán trước, rồi mới chọn phương pháp. Tất cả đều không thu phí.',
    content: `## Châm cứu
Giảm đau vai gáy, đau lưng, tê bì chân tay và di chứng sau tai biến.

## Vật lý trị liệu — phục hồi chức năng
Tập phục hồi vận động sau chấn thương, thoái hoá khớp và cứng khớp.

## Xoa bóp — bấm huyệt
Thư giãn cơ, giảm mỏi và hỗ trợ giấc ngủ, thường kết hợp cùng châm cứu.

## Bốc thuốc — thuốc Nam, thuốc Bắc
Bắt mạch, kê thang thuốc Nam – thuốc Bắc hoặc thuốc y học cổ truyền bào chế sẵn. Bà con nhận thuốc tại chỗ, không mất tiền.`,
  },
  {
    slug: 'huong-dan',
    title: 'Hướng dẫn đi khám',
    summary: 'Cô chú có thể đăng ký trước trên mạng cho đỡ phải chờ, hoặc cứ tới thẳng phòng khám trong giờ làm việc. Cách nào cũng được.',
    content: `## Đặt lịch trên website
1. **Đăng nhập** bằng số điện thoại. Chưa có tài khoản thì tạo một lần, lần sau khỏi nhập lại.
2. **Đặt lịch**: chọn nội dung khám, ngày, khung giờ và người đi khám rồi bấm đặt lịch.
3. **Nhận phiếu khám** ngay, có mã phiếu khám và giờ khám dự kiến, xem trong mục Phiếu khám bệnh.
4. **Đến khám**: đưa phiếu khám cho tiếp tân để check-in. Không đến được thì huỷ phiếu ngay trên trang.

Giờ trên phiếu là giờ dự kiến. Thứ tự vào khám do hàng đợi tại phòng khám sắp xếp: ai có phiếu đặt trước và đến đúng khung giờ được xếp lên trước.

## Quy trình tại phòng khám
1. **Đăng ký**: tình nguyện viên hoặc nhân viên tiếp đón, ghi nhận thông tin.
2. **Thăm khám**: bác sĩ/y sĩ y học cổ truyền xem mạch, chẩn đoán và đưa ra phác đồ điều trị.
3. **Trị liệu tại chỗ** (nếu có): châm cứu, bấm huyệt hoặc xoa bóp.
4. **Nhận thuốc** (nếu có chỉ định) rồi ra về.

## Lưu ý khi đến khám
- **Mang theo hồ sơ bệnh án cũ** nếu có: phim X-quang, MRI, đơn thuốc đang dùng.
- **Mặc quần áo rộng rãi**, ống rộng hoặc cài nút, để thuận tiện châm cứu, xoa bóp.
- **Không để bụng quá đói** khi đi châm cứu vì dễ bị choáng (vựng châm); cũng không ăn quá no ngay trước giờ khám.
- Buổi sáng thường rất đông, nên đến sớm đầu giờ hoặc gọi trước cho phòng khám.`,
  },
  {
    slug: 'lien-he',
    title: 'Liên hệ phòng khám',
    summary: 'Gọi điện là cách nhanh nhất — có người trực nghe máy trong giờ làm việc. Cô chú cũng có thể tới thẳng phòng khám, không cần hẹn trước.',
    content: `**Địa chỉ:** 57/8-10 Điện Biên Phủ, Phường 25 (trước đây thuộc P.15), Quận Bình Thạnh, TP. Hồ Chí Minh.

**Điện thoại:** 0397 116 666 hoặc 0835 555 115 (Sư cô Chúc Từ).

**Giờ nhận bệnh:** 8:00 – 16:30 tất cả các ngày trong tuần, kể cả Chủ Nhật.

Lịch làm việc của các bác sĩ có thể thay đổi, có ngày rất đông bệnh nhân. Bà con nên gọi trước để hỏi khung giờ nhận bệnh trong ngày.`,
  },
];

const DEMO_POSTS = [
  {
    title: 'Lịch khám trong dịp lễ sắp tới',
    category: 'TIN_TUC',
    summary: 'Phòng khám nghỉ hai ngày lễ, các ngày còn lại vẫn nhận bệnh bình thường 8:00 – 16:30.',
    content: `Phòng khám sẽ thông báo cụ thể hai ngày nghỉ lễ trên trang này.

Các ngày còn lại phòng khám vẫn nhận bệnh bình thường từ **8:00 đến 16:30**. Cô chú đã có phiếu khám vào ngày nghỉ sẽ được phòng khám gọi điện hẹn lại.`,
    daysAgo: 1,
  },
  {
    title: 'Cần chuẩn bị gì trước buổi châm cứu đầu tiên',
    category: 'HUONG_DAN',
    summary: 'Cô chú nên ăn nhẹ, mặc đồ rộng rãi và mang theo toa thuốc đang uống khi tới khám.',
    content: `Buổi châm cứu đầu tiên sẽ nhẹ nhàng hơn nếu cô chú chuẩn bị trước vài điều:

- **Ăn nhẹ trước khi đến**, không để bụng đói.
- **Mặc đồ rộng rãi**, dễ vén tay áo, ống quần.
- **Mang theo toa thuốc** đang uống và kết quả khám cũ nếu có.

Nếu thấy chóng mặt, cồn cào trong lúc châm, cô chú báo ngay cho bác sĩ.`,
    daysAgo: 3,
  },
  {
    title: 'Giờ nhận bệnh và lưu ý khi đến khám',
    category: 'TIN_TUC',
    summary: 'Phòng khám nhận bệnh 8:00 – 16:30 mỗi ngày, kể cả Chủ Nhật. Buổi sáng thường rất đông.',
    content: `Phòng khám nhận bệnh từ **8:00 đến 16:30** tất cả các ngày trong tuần, kể cả Chủ Nhật.

Buổi sáng thường rất đông. Để khỏi chờ lâu, cô chú nên đặt lịch trước trên website hoặc gọi điện cho phòng khám.`,
    daysAgo: 7,
  },
];

async function ensureStaff(phone, email, fullName, role) {
  const existing = await User.findOne({ phone });
  if (existing) return existing;
  return User.create({
    phone,
    email,
    fullName,
    role,
    passwordHash: await hashPassword(DEFAULT_PASSWORD),
    mustChangePassword: false,
  });
}

async function seedPages(admin) {
  for (const page of PAGES) {
    if (await Post.exists({ slug: page.slug })) continue;
    await Post.create({
      ...page,
      category: 'TRANG',
      authorId: admin._id,
      authorName: 'Phòng khám Chúc Từ',
      status: 'PUBLISHED',
      publishedAt: new Date(),
    });
  }
}

/** Tạo phiếu trực tiếp (bỏ qua kiểm tra giờ) để có dữ liệu mẫu cho hôm nay. */
async function demoAppointment(patient, user, createdBy, dateStr, start, serviceType, source = 'ONLINE') {
  await SlotCounter.updateOne({ date: dateStr, start }, { $inc: { count: 1 } }, { upsert: true });
  const seq = await nextSeq(`ticket-${dateStr}`);
  return Appointment.create({
    ticketCode: `CT-PK-${yy(dateStr)}-${ddmm(dateStr)}-${String(seq).padStart(3, '0')}`,
    userId: user?._id ?? null,
    patientId: patient._id,
    serviceType,
    slotDate: slotDateValue(dateStr),
    slotStart: start,
    expectedTime: slotInstant(dateStr, start),
    status: 'BOOKED',
    source,
    createdBy: createdBy._id,
    createdAt: new Date(Date.now() - 2 * 86400_000),
  });
}

async function seedDemo(admin, receptionist) {
  if (await User.exists({ phone: '0912345678' })) return;
  const today = todayVN();

  for (const p of DEMO_POSTS) {
    await Post.create({
      title: p.title,
      category: p.category,
      summary: p.summary,
      content: p.content,
      authorId: admin._id,
      authorName: 'Phòng khám Chúc Từ',
      status: 'PUBLISHED',
      publishedAt: new Date(Date.now() - p.daysAgo * 86400_000),
    });
  }
  await Post.create({
    title: 'Bản nháp: Buổi khám thiện nguyện trong phường',
    category: 'TIN_TUC',
    summary: 'Bài viết đang soạn, chưa đăng.',
    content: '',
    authorId: admin._id,
    authorName: admin.fullName,
    status: 'DRAFT',
  });

  const user = await User.create({
    phone: '0912345678',
    fullName: 'Nguyễn Thị Hoa',
    dob: new Date('1958-03-12T00:00:00Z'),
    gender: 'NU',
    address: 'Phường 25, Quận Bình Thạnh, TP. Hồ Chí Minh',
    role: 'USER',
  });
  const self = await createPatient(
    { fullName: user.fullName, dob: '1958-03-12', gender: 'NU', phone: user.phone, address: user.address },
    { userId: user._id, relationship: 'BAN_THAN', createdBy: user._id },
  );
  const husband = await createPatient(
    { fullName: 'Trần Văn Bình', dob: '1955-08-20', gender: 'NAM', phone: '0912345678' },
    { userId: user._id, relationship: 'VO_CHONG', createdBy: user._id },
  );

  await demoAppointment(self, user, user, addDays(today, 2), '09:00', 'CHAM_CUU');
  await demoAppointment(husband, user, user, today, '14:00', 'VAT_LY_TRI_LIEU');

  // Vài bệnh nhân đến trực tiếp trong ngày cho màn Lịch trong ngày và Hàng đợi
  const walkIns = [
    ['Lê Văn Tám', 1950, '0903111222', '08:00', 'CHAM_CUU'],
    ['Phạm Thị Lan', 1962, '0903333444', '08:00', 'XOA_BOP_BAM_HUYET'],
    ['Võ Minh Châu', 1948, '0903555666', '09:00', 'BOC_THUOC'],
    ['Đặng Thị Mai', 1970, '0903777888', '10:00', 'CHUA_RO'],
  ];
  for (const [fullName, birthYear, phone, start, service] of walkIns) {
    const p = await createPatient({ fullName, birthYear, phone }, { createdBy: receptionist._id });
    await demoAppointment(p, null, receptionist, today, start, service, 'COUNTER');
  }
  // Lịch trong ngày, hàng đợi, lịch sử khám… cho khu Tiếp tân và Admin
  await seedClinicDemo({ receptionist });
}

export async function seed({ demo = false } = {}) {
  const admin = await ensureStaff('0900000001', 'admin@chuctu.vn', 'Quản trị viên', 'ADMIN');
  const receptionist = await ensureStaff('0900000002', 'letan@chuctu.vn', 'Trần Thị Thu', 'RECEPTIONIST');
  await seedPages(admin);
  if (demo) await seedDemo(admin, receptionist);
  console.log(
    `Dữ liệu mẫu sẵn sàng. Admin: admin@chuctu.vn, Tiếp tân: letan@chuctu.vn, mật khẩu ${DEFAULT_PASSWORD}` +
      (demo ? '. Người dùng mẫu: 0912345678.' : '.'),
  );
  return { admin, receptionist, patients: await Patient.countDocuments() };
}

// Chạy trực tiếp: node src/seed.js [--demo]
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { connectDb, disconnectDb } = await import('./db.js');
  await connectDb();
  await seed({ demo: process.argv.includes('--demo') });
  await disconnectDb();
}
