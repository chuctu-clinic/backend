import { config } from '../config.ts';

/**
 * Verification Code Gateway: gửi mã 6 số tới số điện thoại.
 *
 * - console: in mã ra log server (dùng khi phát triển và kiểm thử).
 * - firebase: chỗ nối dịch vụ gửi tin nhắn thật. Firebase Phone Auth tự sinh và gửi mã từ phía
 *   trình duyệt (cần reCAPTCHA), nên khi chọn Firebase cần thống nhất lại luồng với nhóm — xem README.
 */
const providers: Record<string, (phone: string, code: string, purpose: string) => Promise<void>> = {
  async console(phone: string, code: string, purpose: string) {
    if (!config.isTest) console.log(`[OTP] ${purpose} ${phone}: ${code}`);
  },
  async firebase() {
    throw new Error('Chưa cấu hình dịch vụ gửi tin nhắn Firebase (SMS_PROVIDER=firebase).');
  },
};

export async function sendVerificationCode(phone: string, code: string, purpose: string) {
  const send = providers[config.smsProvider];
  if (!send) throw new Error(`SMS_PROVIDER không hợp lệ: ${config.smsProvider}`);
  await send(phone, code, purpose);
}

/** Khi phát triển, trả mã về cho giao diện để thử luồng mà không cần điện thoại thật. */
export const exposeCodeForDev = () => !config.isProd && config.smsProvider === 'console';
