import 'dotenv/config';

const env = process.env.NODE_ENV || 'development';

export const config = {
  env,
  isProd: env === 'production',
  isTest: env === 'test',
  port: Number(process.env.PORT || 4000),
  mongoUri: process.env.MONGODB_URI || '',
  jwtSecret: process.env.JWT_SECRET || 'dev-secret-chuc-tu',
  clientOrigin: (process.env.CLIENT_ORIGIN || 'http://localhost:5173').split(',').map((s) => s.trim()),
  smsProvider: process.env.SMS_PROVIDER || 'console',
  // Firebase Phone Auth (SMS_PROVIDER=firebase): trình duyệt gửi và xác nhận mã, server kiểm ID token.
  // Các giá trị web là công khai (giống cấu hình Web app trên Firebase Console), server chỉ cần projectId.
  firebase: {
    projectId: process.env.FIREBASE_PROJECT_ID || '',
    apiKey: process.env.FIREBASE_API_KEY || '',
    authDomain: process.env.FIREBASE_AUTH_DOMAIN || '',
    appId: process.env.FIREBASE_APP_ID || '',
    // Ví dụ 127.0.0.1:9099: dùng Firebase Auth Emulator, không gửi SMS thật (firebase-admin tự đọc biến này)
    emulatorHost: process.env.FIREBASE_AUTH_EMULATOR_HOST || '',
    // Chỉ dùng số thử trên Firebase Console, bỏ qua reCAPTCHA (để thử tự động). Không có tác dụng khi production.
    testNumbersOnly: env !== 'production' && process.env.FIREBASE_TEST_NUMBERS_ONLY === '1',
  },
};

// Quy tắc nghiệp vụ dùng chung (Business Rule BR.03, BR.12–BR.14, BR.29)
export const rules = {
  otp: {
    length: 6,
    ttlMinutes: 5,
    maxPerHour: 3,
    resendSeconds: 60,
    maxAttempts: 5,
  },
  maxProfilesPerAccount: 10,
  booking: {
    userMinDays: 0, // hôm nay, chỉ các khung chưa bắt đầu
    userMaxDays: 30,
    counterMaxDays: 7,
    slotCapacity: 6,
    slots: ['08:00', '09:00', '10:00', '11:00', '13:00', '14:00', '15:00', '16:00'],
    slotMinutes: 60,
  },
  queue: {
    seats: 5,
    // Đến sớm tối đa bao nhiêu phút trước khung giờ vẫn được tính là "đúng khung giờ"
    priorityEarlyMinutes: 15,
  },
  session: {
    days: 30,
  },
  minPasswordLength: 8,
};

// Khoá ký phiên và băm mã xác nhận: khi production không được dùng giá trị mặc định, chuỗi mẫu trong
// .env.example / docker-compose.yml (ai đọc mã nguồn cũng biết) hay chuỗi quá ngắn.
const KNOWN_SECRETS = ['dev-secret-chuc-tu', 'doi-chuoi-nay-khi-trien-khai'];
if (config.isProd && (KNOWN_SECRETS.includes(config.jwtSecret) || config.jwtSecret.length < 32)) {
  throw new Error('JWT_SECRET phải là chuỗi ngẫu nhiên riêng, dài ít nhất 32 ký tự khi chạy production');
}
