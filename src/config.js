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

if (config.isProd && config.jwtSecret === 'dev-secret-chuc-tu') {
  throw new Error('JWT_SECRET phải được đặt khi chạy production');
}
