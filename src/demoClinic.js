import { rules } from './config.js';
import { Appointment, Checkin, MedicalRecord, Patient, SlotCounter, User, nextSeq, setSetting } from './models/index.js';
import { createPatient } from './services/patient.service.js';
import { addDays, ddmm, minutesOfDayVN, slotDateValue, slotInstant, todayVN, yy } from './utils/time.js';

/*
 * Dữ liệu mẫu cho khu Tiếp tân và Admin, chỉ dùng ở chế độ demo (CSDL trong bộ nhớ).
 * Mọi mốc giờ tính theo lúc khởi động server, nên mở lúc nào cũng có một ngày làm việc "đang diễn ra":
 *  - Lịch trong ngày: đủ trạng thái Đã đặt / Đã check-in / Đang khám / Đã hoàn thành / Đã huỷ, cả online lẫn tại quầy.
 *  - Hàng đợi: 4/5 ghế có người (ghế 3 trống), người chờ nhóm ưu tiên và nhóm thường; tắt tự động mời.
 *  - Hồ sơ bệnh nhân: ~40 hồ sơ, lịch sử khám 2 tháng kèm phiếu kết quả, 1 hồ sơ bị khoá, vài hồ sơ trùng số điện thoại.
 *  - Các ngày tới: phiếu đã đặt để thử đặt hộ, đổi giờ; khung 9:00 ngày mai đã đủ chỗ.
 * Phiếu và check-in được ghi thẳng vào CSDL (không qua luật "khung giờ đã qua"), nên mở buổi tối vẫn có dữ liệu.
 */

const { slots, slotCapacity } = rules.booking;
const MIN = 60_000;

// Số giả ngẫu nhiên cố định để mỗi lần khởi động ra cùng một bộ dữ liệu
let state = 20261021;
const rand = () => {
  state = (state * 1103515245 + 12345) % 2147483648;
  return state / 2147483648;
};
const pick = (arr) => arr[Math.floor(rand() * arr.length)];
const between = (a, b) => a + Math.floor(rand() * (b - a + 1));

const WARDS = [
  'Phường Bình Thạnh, TP. Hồ Chí Minh',
  'Phường Gia Định, TP. Hồ Chí Minh',
  'Phường Thạnh Mỹ Tây, TP. Hồ Chí Minh',
  'Phường Bình Lợi Trung, TP. Hồ Chí Minh',
  'Phường Sài Gòn, TP. Hồ Chí Minh',
  'Phường Bến Thành, TP. Hồ Chí Minh',
  'Phường Hạnh Thông, TP. Hồ Chí Minh',
  'Xã Long Thành, Tỉnh Đồng Nai',
];
const STREETS = ['Điện Biên Phủ', 'Xô Viết Nghệ Tĩnh', 'Nơ Trang Long', 'Bạch Đằng', 'Phan Đăng Lưu', 'Lê Quang Định', 'Nguyễn Xí', 'Đinh Bộ Lĩnh'];

// [họ tên, giới tính, năm sinh]
const PEOPLE = [
  ['Lý Thị Ngọc', 'NU', 1951], ['Trương Văn Phúc', 'NAM', 1946], ['Huỳnh Thị Sen', 'NU', 1955], ['Đỗ Văn Lộc', 'NAM', 1960],
  ['Mai Thị Hồng', 'NU', 1949], ['Phan Văn Tài', 'NAM', 1958], ['Bùi Thị Thu', 'NU', 1963], ['Hồ Văn Sơn', 'NAM', 1952],
  ['Lâm Thị Kim', 'NU', 1957], ['Dương Văn Hải', 'NAM', 1944], ['Châu Thị Nga', 'NU', 1966], ['Tạ Văn Quang', 'NAM', 1950],
  ['Quách Thị Yến', 'NU', 1959], ['Kiều Văn Đức', 'NAM', 1947], ['Tô Thị Hương', 'NU', 1968], ['Lưu Văn Thành', 'NAM', 1955],
  ['Vương Thị Liên', 'NU', 1945], ['Cao Văn Minh', 'NAM', 1962], ['Đinh Thị Xuân', 'NU', 1953], ['La Văn Bảo', 'NAM', 1971],
  ['Trịnh Thị Cúc', 'NU', 1948], ['Hà Văn Nghĩa', 'NAM', 1956], ['Từ Thị Mận', 'NU', 1961], ['Ông Văn Tư', 'NAM', 1943],
  ['Thái Thị Hoa', 'NU', 1964], ['Diệp Văn Long', 'NAM', 1969], ['Âu Thị Lành', 'NU', 1950], ['Mạc Văn Toàn', 'NAM', 1958],
  ['Lương Thị Bích', 'NU', 1975], ['Phùng Văn Khải', 'NAM', 1954],
];

// Mẫu phiếu kết quả khám: chẩn đoán, mạch, huyết áp, ghi chú điều trị, nội dung khám
const RECORDS = [
  { s: 'CHAM_CUU', d: 'Đau mỏi vai gáy, tê tay phải do thoái hoá đốt sống cổ.', p: '78', bp: '130/80', n: { acupuncture: 'Châm cứu vùng vai gáy và tay phải, lưu kim 20 phút.', acupressure: 'Xoa bóp bấm huyệt vùng vai gáy.' } },
  { s: 'CHAM_CUU', d: 'Đau thắt lưng, đau lan xuống chân trái.', p: '72', bp: '140/85', n: { acupuncture: 'Châm cứu vùng thắt lưng và chân trái, lưu kim 20 phút.', other: 'Nằm giường cứng, tránh bê vác nặng.' } },
  { s: 'XOA_BOP_BAM_HUYET', d: 'Co cứng cơ vùng lưng, mỏi vai do ngồi lâu.', p: '76', bp: '125/80', n: { acupressure: 'Xoa bóp bấm huyệt vùng lưng và vai, 30 phút.', other: 'Tập vươn vai nhẹ mỗi giờ khi ngồi làm việc.' } },
  { s: 'VAT_LY_TRI_LIEU', d: 'Thoái hoá khớp gối hai bên, đi lại khó khăn.', p: '80', bp: '135/85', n: { other: 'Tập vận động khớp gối, chườm nóng 15 phút. Tập đi có người đỡ.' } },
  { s: 'BOC_THUOC', d: 'Mệt mỏi, ăn ngủ kém, khí huyết yếu.', p: '82', bp: '120/75', n: { other: 'Bốc 7 thang thuốc Nam bổ khí huyết, sắc uống sau ăn sáng và tối.' } },
  { s: 'CHAM_CUU', d: 'Di chứng tai biến, yếu nửa người trái.', p: '74', bp: '150/90', n: { acupuncture: 'Châm cứu phục hồi tay chân bên trái, lưu kim 25 phút.', acupressure: 'Xoa bóp tay chân bên trái.', other: 'Tập cầm nắm bằng tay trái tại nhà.' } },
  { s: 'CHUA_RO', d: 'Mất ngủ kéo dài, hay đau đầu về chiều.', p: '84', bp: '128/82', n: { acupuncture: 'Châm cứu an thần, lưu kim 20 phút.', other: 'Hạn chế trà, cà phê sau 15 giờ.' } },
  { s: 'XOA_BOP_BAM_HUYET', d: 'Chuột rút ban đêm, tê bì hai bàn chân.', p: '70', bp: '118/76', n: { acupressure: 'Bấm huyệt hai chân, ngâm chân nước ấm.', other: 'Uống đủ nước, ngâm chân nước ấm trước khi ngủ.' } },
];
const HISTORIES = [
  'Cao huyết áp: đang uống thuốc hạ huyết áp mỗi sáng.',
  'Đái tháo đường típ 2, uống thuốc theo toa.',
  'Phẫu thuật, chấn thương: gãy tay trái năm 2015.',
  'Dị ứng thuốc: dị ứng penicillin.',
  'Hút thuốc lá hơn 20 năm, đã bỏ được 3 năm.',
  null,
  null,
];
const DOCTORS = ['BS. Nguyễn Văn An', 'YS. Trần Thị Bích', 'BS. Lê Minh Tuấn'];
const SERVICES = ['CHAM_CUU', 'XOA_BOP_BAM_HUYET', 'VAT_LY_TRI_LIEU', 'BOC_THUOC', 'CHUA_RO'];
const CANCEL_REASONS = ['Bận việc đột xuất', 'Sức khoẻ không đi được', 'Muốn đổi sang ngày giờ khác'];

/** Khung giờ đang diễn ra lúc khởi động (ngoài giờ làm việc thì lấy khung gần nhất). */
function currentSlot(now) {
  const m = minutesOfDayVN(now);
  const started = slots.filter((s) => {
    const [h, mm] = s.split(':').map(Number);
    return h * 60 + mm <= m;
  });
  return started.length ? started[started.length - 1] : slots[0];
}

/** Chọn khung còn chỗ, ưu tiên các khung trong danh sách; trả về null nếu ngày đã kín. */
async function freeSlot(date, preferred = slots) {
  for (const start of [...preferred, ...slots]) {
    const c = await SlotCounter.findOne({ date, start }).lean();
    if ((c?.count || 0) < slotCapacity) return start;
  }
  return null;
}

async function makeAppt({ patient, user = null, createdBy, date, start, service, source, status = 'BOOKED', createdAt, cancel }) {
  if (status !== 'CANCELLED') await SlotCounter.updateOne({ date, start }, { $inc: { count: 1 } }, { upsert: true });
  const seq = await nextSeq(`ticket-${date}`);
  return Appointment.create({
    ticketCode: `CT-PK-${yy(date)}-${ddmm(date)}-${String(seq).padStart(3, '0')}`,
    userId: user?._id ?? null,
    patientId: patient._id,
    serviceType: service,
    slotDate: slotDateValue(date),
    slotStart: start,
    expectedTime: slotInstant(date, start),
    status,
    source,
    createdBy: createdBy._id,
    createdAt: createdAt ?? new Date(slotInstant(date, start).getTime() - between(1, 6) * 86400_000),
    ...(cancel ? { cancelReason: cancel.reason, cancelledBy: cancel.by._id, cancelledAt: cancel.at } : {}),
  });
}

async function makeRecord({ appt, checkin, date, staff, tpl, history }) {
  const followUp = rand() < 0.6 ? addDays(date, pick([7, 10, 14])) : null;
  // Phiếu được xác nhận sau giờ khám khoảng 40 phút, để hạn sửa 24 giờ tính đúng theo ngày khám
  const confirmedAt = new Date(Math.min(Date.now(), slotInstant(date, appt.slotStart).getTime() + 40 * 60_000));
  return MedicalRecord.create({
    createdAt: confirmedAt,
    updatedAt: confirmedAt,
    patientId: appt.patientId,
    appointmentId: appt._id,
    checkinId: checkin?._id ?? null,
    visitDate: slotDateValue(date),
    diagnosis: tpl.d,
    pulse: tpl.p,
    bloodPressure: tpl.bp,
    treatmentNotes: tpl.n,
    medicalHistory: history,
    followUpDate: followUp ? slotDateValue(followUp) : null,
    doctorName: pick(DOCTORS),
    recordedBy: staff._id,
  });
}

/** Một lượt khám đã xong trong quá khứ: phiếu Đã hoàn thành, check-in, phiếu kết quả. */
async function pastVisit({ patient, user, staff, date, history }) {
  const tpl = pick(RECORDS);
  const start = pick(slots);
  const online = Boolean(user) && rand() < 0.6;
  const appt = await makeAppt({
    patient,
    user: online ? user : null,
    createdBy: online ? user : staff,
    date,
    start,
    service: tpl.s,
    source: online ? 'ONLINE' : 'COUNTER',
    status: 'COMPLETED',
  });
  const checkInTime = new Date(slotInstant(date, start).getTime() + between(-10, 20) * MIN);
  const enteredAt = new Date(checkInTime.getTime() + between(5, 30) * MIN);
  const checkin = await Checkin.create({
    appointmentId: appt._id,
    patientId: patient._id,
    visitDate: date,
    checkInTime,
    priorityGroup: online ? 'PRIORITY' : 'NORMAL',
    status: 'COMPLETED',
    seatNo: between(1, 5),
    enteredAt,
    completedAt: new Date(enteredAt.getTime() + between(20, 45) * MIN),
  });
  await makeRecord({ appt, checkin, date, staff, tpl, history });
}

export async function seedClinicDemo({ receptionist }) {
  const now = new Date();
  const today = todayVN(now);
  const cur = currentSlot(now);
  const curIdx = slots.indexOf(cur);
  const earlier = slots.slice(0, Math.max(1, curIdx));
  const later = slots.slice(curIdx + 1).length ? slots.slice(curIdx + 1) : [cur];
  const ago = (min) => new Date(now.getTime() - min * MIN);

  // ---------- Tài khoản người dùng và hồ sơ ----------
  const accounts = [];
  for (const [i, [fullName, gender, year]] of PEOPLE.slice(0, 16).entries()) {
    const phone = `09080000${String(i + 1).padStart(2, '0')}`;
    const dob = `${year}-${String(between(1, 12)).padStart(2, '0')}-${String(between(1, 28)).padStart(2, '0')}`;
    const address = `${between(5, 220)} ${pick(STREETS)}, ${pick(WARDS)}`;
    const user = await User.create({ phone, fullName, gender, dob: new Date(`${dob}T00:00:00Z`), address, role: 'USER' });
    const self = await createPatient({ fullName, dob, gender, phone, address }, { userId: user._id, relationship: 'BAN_THAN', createdBy: user._id });
    accounts.push({ user, patient: self });
  }
  // Hai tài khoản có thêm hồ sơ người thân
  const relatives = [
    await createPatient({ fullName: 'Trương Thị Mỹ', birthYear: 1950, gender: 'NU', phone: accounts[1].user.phone }, { userId: accounts[1].user._id, relationship: 'VO_CHONG', createdBy: accounts[1].user._id }),
    await createPatient({ fullName: 'Huỳnh Văn Lợi', birthYear: 1983, gender: 'NAM', phone: '0908100200' }, { userId: accounts[2].user._id, relationship: 'CON', createdBy: accounts[2].user._id }),
  ];
  accounts.push({ user: accounts[1].user, patient: relatives[0] }, { user: accounts[2].user, patient: relatives[1] });

  // Hồ sơ do tiếp tân lập tại quầy (chỉ cần họ tên, năm sinh, số điện thoại)
  const walkIns = [];
  for (const [i, [fullName, gender, birthYear]] of PEOPLE.slice(16).entries()) {
    const phone = `09070001${String(i + 10).padStart(2, '0')}`;
    walkIns.push(
      await createPatient(
        { fullName, gender, birthYear, phone, address: rand() < 0.7 ? `${between(5, 220)} ${pick(STREETS)}, ${pick(WARDS)}` : null },
        { createdBy: receptionist._id },
      ),
    );
  }
  // Trùng số điện thoại với hồ sơ có sẵn: thử cảnh báo trùng khi lập hồ sơ mới
  const dupPhone = walkIns[0].phone;
  walkIns.push(await createPatient({ fullName: 'Lý Văn Hoà', gender: 'NAM', birthYear: 1949, phone: dupPhone }, { createdBy: receptionist._id }));
  walkIns.push(await createPatient({ fullName: 'Lý Thị Ngọc', gender: 'NU', birthYear: 1952, phone: '0907000199' }, { createdBy: receptionist._id }));

  // Hồ sơ bị khoá: có lịch sử khám, không đặt lịch / check-in được nữa
  const locked = await createPatient(
    { fullName: 'Nguyễn Văn Khoá', gender: 'NAM', birthYear: 1957, phone: '0907000177', address: `12 Bạch Đằng, ${WARDS[0]}` },
    { createdBy: receptionist._id },
  );

  // ---------- Lịch sử khám 2 tháng gần đây ----------
  const everyone = [...accounts.map((a) => ({ patient: a.patient, user: a.user })), ...walkIns.map((p) => ({ patient: p, user: null }))];
  for (const { patient, user } of everyone) {
    if (rand() < 0.25) continue;
    const history = pick(HISTORIES);
    const visits = between(1, 4);
    const days = [...new Set(Array.from({ length: visits }, () => between(3, 60)))].sort((a, b) => b - a);
    for (const d of days) await pastVisit({ patient, user, staff: receptionist, date: addDays(today, -d), history });
    // Thỉnh thoảng có phiếu đã huỷ trong quá khứ
    if (rand() < 0.2) {
      const d = addDays(today, -between(2, 40));
      await makeAppt({
        patient,
        user,
        createdBy: user || receptionist,
        date: d,
        start: pick(slots),
        service: pick(SERVICES),
        source: user ? 'ONLINE' : 'COUNTER',
        status: 'CANCELLED',
        cancel: { reason: pick(CANCEL_REASONS), by: user || receptionist, at: new Date(slotInstant(d, '08:00').getTime() - 86400_000) },
      });
    }
  }
  for (const d of [45, 21]) await pastVisit({ patient: locked, user: null, staff: receptionist, date: addDays(today, -d), history: HISTORIES[0] });
  locked.isLocked = true;
  locked.lockedAt = ago(3 * 24 * 60);
  await locked.save();

  // ---------- Hôm nay ----------
  // Mỗi người chỉ có một phiếu trong ngày hôm nay; các ngày tới thì không cần chặn
  const usedToday = new Set();
  const picker = (list) => {
    let i = 0;
    return ({ unique = true } = {}) => {
      for (let k = 0; k < list.length; k += 1) {
        const e = list[i++ % list.length];
        if (!unique || !usedToday.has(e.patient.id)) {
          if (unique) usedToday.add(e.patient.id);
          return e;
        }
      }
      return list[i++ % list.length];
    };
  };
  // Khách đến trực tiếp (không tài khoản) và người đặt online lấy từ hai nhóm riêng
  const take = picker(walkIns.map((p) => ({ patient: p, user: null })));
  const takeOnline = picker(everyone.filter((e) => e.user));

  const todayAppt = async ({ who, start, status, service = pick(SERVICES), cancel }) => {
    const online = Boolean(who.user);
    return makeAppt({
      patient: who.patient,
      user: who.user,
      createdBy: who.user || receptionist,
      date: today,
      start,
      service,
      source: online ? 'ONLINE' : 'COUNTER',
      status,
      // Khách đến trực tiếp được lập phiếu ngay trong ngày
      createdAt: online ? undefined : ago(between(60, 240)),
      cancel,
    });
  };

  // Đã khám xong sáng nay: phiếu Đã hoàn thành kèm phiếu kết quả
  for (let i = 0; i < 6; i += 1) {
    const who = i % 2 ? take() : takeOnline();
    const start = await freeSlot(today, earlier);
    const tpl = pick(RECORDS);
    const appt = await todayAppt({ who, start, status: 'COMPLETED', service: tpl.s });
    const checkInTime = ago(150 + i * 15);
    const enteredAt = new Date(checkInTime.getTime() + between(5, 20) * MIN);
    const checkin = await Checkin.create({
      appointmentId: appt._id,
      patientId: who.patient._id,
      visitDate: today,
      checkInTime,
      priorityGroup: who.user ? 'PRIORITY' : 'NORMAL',
      status: 'COMPLETED',
      seatNo: (i % 5) + 1,
      enteredAt,
      completedAt: new Date(enteredAt.getTime() + between(20, 40) * MIN),
    });
    await makeRecord({ appt, checkin, date: today, staff: receptionist, tpl, history: pick(HISTORIES) });
  }

  // Đang trong phòng khám: ghế 1, 2, 4, 5 (ghế 3 vừa trống)
  for (const [i, seatNo] of [1, 2, 4, 5].entries()) {
    const who = i % 2 ? take() : takeOnline();
    // Lấy khung liền trước để khung hiện tại còn chỗ cho người chờ nhóm ưu tiên
    const start = await freeSlot(today, [...earlier.slice(-1), cur]);
    const appt = await todayAppt({ who, start, status: 'IN_PROGRESS' });
    const checkInTime = ago(45 + i * 8);
    await Checkin.create({
      appointmentId: appt._id,
      patientId: who.patient._id,
      visitDate: today,
      checkInTime,
      priorityGroup: who.user ? 'PRIORITY' : 'NORMAL',
      status: 'IN_ROOM',
      seatNo,
      enteredAt: ago(5 + i * 9),
    });
  }

  // Đang chờ: 3 người nhóm ưu tiên (đặt trước, đến đúng khung giờ), 3 người nhóm thường (khách đến trực tiếp)
  for (let i = 0; i < 6; i += 1) {
    const priority = i < 3;
    const who = priority ? takeOnline() : take();
    // Nhóm ưu tiên phải đúng khung hiện tại; nhóm thường lấy khung nào còn chỗ
    const start = await freeSlot(today, priority ? [cur] : [cur, ...earlier.slice().reverse()]);
    const appt = await todayAppt({ who: priority ? who : { patient: who.patient, user: null }, start, status: 'CHECKED_IN' });
    await Checkin.create({
      appointmentId: appt._id,
      patientId: who.patient._id,
      visitDate: today,
      checkInTime: ago(priority ? 30 - i * 8 : 40 - (i - 3) * 9),
      priorityGroup: priority ? 'PRIORITY' : 'NORMAL',
      status: 'WAITING',
    });
  }

  // Chưa tới: các khung sau trong ngày, và hai người đặt khung trước nhưng chưa đến
  for (let i = 0; i < 7; i += 1) {
    const who = i % 3 === 2 ? take() : takeOnline();
    const start = await freeSlot(today, i < 2 ? earlier : later);
    if (start) await todayAppt({ who, start, status: 'BOOKED' });
  }

  // Đã huỷ hôm nay: một người tự huỷ trên web, một phiếu tiếp tân huỷ giúp qua điện thoại
  const u1 = takeOnline();
  await todayAppt({ who: u1, start: later[0], status: 'CANCELLED', cancel: { reason: 'Sức khoẻ không đi được', by: u1.user, at: ago(180) } });
  await todayAppt({ who: { patient: take().patient, user: null }, start: later[later.length - 1], status: 'CANCELLED', cancel: { reason: 'Gọi điện báo bận việc đột xuất', by: receptionist, at: ago(90) } });

  // ---------- Các ngày tới ----------
  // Khung 9:00 ngày mai kín chỗ để thử "Đã đủ chỗ"
  const tomorrow = addDays(today, 1);
  while ((await freeSlot(tomorrow, ['09:00'])) === '09:00') {
    const who = takeOnline({ unique: false });
    await makeAppt({ patient: who.patient, user: who.user, createdBy: who.user, date: tomorrow, start: '09:00', service: pick(SERVICES), source: 'ONLINE' });
  }
  for (let d = 1; d <= 6; d += 1) {
    const date = addDays(today, d);
    for (let i = 0; i < between(3, 5); i += 1) {
      const who = rand() < 0.6 ? takeOnline({ unique: false }) : { patient: take({ unique: false }).patient, user: null };
      const start = await freeSlot(date, [pick(slots)]);
      await makeAppt({ patient: who.patient, user: who.user, createdBy: who.user || receptionist, date, start, service: pick(SERVICES), source: who.user ? 'ONLINE' : 'COUNTER' });
    }
    if (d % 2 === 0) {
      const who = takeOnline({ unique: false });
      await makeAppt({
        patient: who.patient, user: who.user, createdBy: who.user, date, start: pick(slots), service: pick(SERVICES), source: 'ONLINE', status: 'CANCELLED',
        cancel: { reason: pick(CANCEL_REASONS), by: who.user, at: ago(between(60, 1440)) },
      });
    }
  }

  // Ghế 3 trống và còn người chờ: tắt tự động mời để tiếp tân bấm "Mời người kế tiếp"
  await setSetting('queue.autoInvite', false);
}
