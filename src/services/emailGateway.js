import nodemailer from 'nodemailer';
import { config, rules } from '../config.js';
import { AppError } from '../utils/errors.js';

/**
 * Email Gateway: gửi mã 6 số tới email (quên mật khẩu, đổi email của tài khoản nội bộ).
 *
 * - console: in mã ra log (dùng khi phát triển và kiểm thử). Khi production không gửi được gì nên báo lỗi rõ ràng.
 * - smtp: gửi qua máy chủ SMTP bằng Nodemailer (Gmail, Google Workspace, Brevo…), cấu hình SMTP_* và MAIL_FROM.
 */
const CONTENT = {
  RESET_PASSWORD: {
    subject: 'Phòng khám Chúc Từ - Mã đặt lại mật khẩu',
    intro: 'Bạn vừa yêu cầu đặt lại mật khẩu tài khoản nội bộ của Phòng khám Chúc Từ.',
  },
  CHANGE_EMAIL: {
    subject: 'Phòng khám Chúc Từ - Mã xác minh email',
    intro: 'Bạn vừa đổi email của tài khoản nội bộ Phòng khám Chúc Từ sang địa chỉ này.',
  },
};

function message(code, purpose) {
  const c = CONTENT[purpose] || { subject: 'Mã xác nhận — Phòng khám Chúc Từ', intro: '' };
  const minutes = rules.otp.ttlMinutes;
  const outro = 'Nếu không phải bạn yêu cầu, hãy bỏ qua email này. Không đưa mã cho bất kỳ ai, kể cả nhân viên phòng khám.';
  const text = `${c.intro}\n\nMã xác nhận: ${code}\nMã dùng được trong ${minutes} phút.\n\n${outro}\n\nPhòng khám Chúc Từ`;
  const html = `<div style="font-family:Arial,sans-serif;font-size:16px;line-height:1.6;color:#2a2118;max-width:520px">
  <p>${c.intro}</p>
  <p style="margin:24px 0 8px">Mã xác nhận của bạn:</p>
  <p style="font-size:32px;font-weight:700;letter-spacing:8px;color:#2e5b37;margin:0 0 8px">${code}</p>
  <p>Mã dùng được trong ${minutes} phút.</p>
  <p style="color:#6b5e4e">${outro}</p>
  <p>Phòng khám Chúc Từ</p>
</div>`;
  return { subject: c.subject, text, html };
}

/** Bộ gửi SMTP, tạo một lần. Kiểm thử thay hàm create để không gửi thật. */
export const smtpTransport = {
  instance: null,
  create() {
    const { host, port, user, pass } = config.smtp;
    return nodemailer.createTransport({ host, port, secure: port === 465, auth: { user, pass } });
  },
  get() {
    if (!this.instance) this.instance = this.create();
    return this.instance;
  },
};

const providers = {
  async console(email, code, purpose) {
    if (config.isProd) {
      throw new AppError(503, 'EMAIL_UNAVAILABLE', 'Phòng khám chưa bật gửi email. Cô chú vui lòng liên hệ quản trị viên để được cấp mật khẩu tạm.');
    }
    if (!config.isTest) console.log(`[EMAIL] ${CONTENT[purpose]?.subject || purpose} → ${email}: ${code}`);
  },
  async smtp(email, code, purpose) {
    try {
      await smtpTransport.get().sendMail({ from: config.mailFrom, to: email, ...message(code, purpose) });
    } catch (err) {
      console.error('[EMAIL] Gửi qua SMTP không được:', err.message);
      throw new AppError(502, 'EMAIL_FAILED', 'Chưa gửi được email. Cô chú thử lại sau ít phút hoặc liên hệ quản trị viên.');
    }
  },
};

export async function sendEmailCode(email, code, purpose) {
  const send = providers[config.emailProvider];
  if (!send) throw new Error(`EMAIL_PROVIDER không hợp lệ: ${config.emailProvider}`);
  await send(email, code, purpose);
}

/** Mã mẫu cố định (mặc định 123456) để thử luồng khi phát triển; kiểm thử và production luôn dùng mã ngẫu nhiên. */
export const devFixedEmailCode = () => (config.env === 'development' && config.emailProvider === 'console' ? config.emailDevCode : null);

/** Khi phát triển, trả mã về cho giao diện để thử luồng mà không cần hộp thư thật. */
export const exposeEmailCodeForDev = () => !config.isProd && config.emailProvider === 'console';
