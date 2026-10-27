import { config } from '../config.js';

/**
 * Verification Code Gateway: gửi mã 6 số tới số điện thoại.
 *
 * - console: server sinh mã, in ra log (dùng khi phát triển và kiểm thử).
 * - firebase: Firebase Phone Auth. Trình duyệt tự gửi và xác nhận mã (kèm reCAPTCHA), server không gửi gì,
 *   chỉ ghi nhận lần xin mã để giữ giới hạn 3 mã mỗi giờ, rồi kiểm ID token Firebase trả về.
 *
 * Đổi sang nhà cung cấp SMS có API phía server (eSMS, SpeedSMS…): thêm một provider gửi tin ở đây,
 * phần còn lại (mã băm, giới hạn, thiết bị tin cậy) giữ nguyên.
 */
const providers = {
  async console(phone, code, purpose) {
    if (!config.isTest) console.log(`[OTP] ${purpose} ${phone}: ${code}`);
  },
  async firebase() {
    /* Trình duyệt gửi tin qua Firebase */
  },
};

/** Mã do trình duyệt gửi (Firebase) hay do server sinh và gửi. */
export const usesFirebase = () => config.smsProvider === 'firebase';

export async function sendVerificationCode(phone, code, purpose) {
  const send = providers[config.smsProvider];
  if (!send) throw new Error(`SMS_PROVIDER không hợp lệ: ${config.smsProvider}`);
  await send(phone, code, purpose);
}

/** Khi phát triển, trả mã về cho giao diện để thử luồng mà không cần điện thoại thật. */
export const exposeCodeForDev = () => !config.isProd && config.smsProvider === 'console';

/** Cấu hình Web app Firebase gửi cho giao diện (đều là giá trị công khai). */
export function firebaseWebConfig() {
  if (!usesFirebase()) return null;
  const { apiKey, authDomain, projectId, appId, emulatorHost, testNumbersOnly } = config.firebase;
  return { apiKey, authDomain, projectId, appId, emulatorUrl: emulatorHost ? `http://${emulatorHost}` : null, testNumbersOnly };
}

let adminAuth = null;
async function getAdminAuth() {
  if (adminAuth) return adminAuth;
  if (!config.firebase.projectId) throw new Error('Thiếu FIREBASE_PROJECT_ID trong server/.env.');
  const { getApps, initializeApp } = await import('firebase-admin/app');
  const { getAuth } = await import('firebase-admin/auth');
  // Chỉ cần projectId để kiểm ID token (khoá công khai của Google tự tải về); có FIREBASE_AUTH_EMULATOR_HOST thì kiểm với emulator
  const app = getApps()[0] || initializeApp({ projectId: config.firebase.projectId });
  adminAuth = getAuth(app);
  return adminAuth;
}

/**
 * ID token Firebase → { phone (dạng +84…), authTime (ms) }. Ném lỗi nếu token sai hoặc hết hạn.
 * Kiểm thử thay hàm này qua firebaseVerifier.verify.
 */
export const firebaseVerifier = {
  async verify(idToken) {
    const decoded = await (await getAdminAuth()).verifyIdToken(idToken);
    return { phone: decoded.phone_number || null, authTime: decoded.auth_time * 1000 };
  },
};
