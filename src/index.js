import { createApp } from './app.js';
import { config } from './config.js';
import { connectDb } from './db.js';
import { seed } from './seed.js';
import { autoCancelNoShows } from './services/appointment.service.js';

// --demo: luôn dùng MongoDB trong bộ nhớ kèm dữ liệu mẫu, kể cả khi .env có MONGODB_URI (không đụng CSDL thật)
const demo = process.argv.includes('--demo');
if (demo && config.isProd) throw new Error('Không chạy chế độ --demo khi NODE_ENV=production');

const inMemory = await connectDb(demo ? '' : config.mongoUri);
if (inMemory) {
  console.log(
    demo
      ? 'Chế độ demo: đang dùng MongoDB trong bộ nhớ với dữ liệu mẫu (không đụng CSDL thật, dữ liệu mất khi tắt server).'
      : 'Chưa đặt MONGODB_URI: đang dùng MongoDB trong bộ nhớ (dữ liệu mất khi tắt server).',
  );
  await seed({ demo: true });
}

createApp().listen(config.port, () => {
  console.log(`API Phòng khám Chúc Từ chạy tại http://localhost:${config.port}`);
});

// Từ 17:00, phiếu hôm nay còn Chưa đến tự chuyển sang Đã huỷ. Chạy ngay khi khởi động (huỷ bù phiếu bị bỏ sót
// lúc server tắt) rồi mỗi phút một lần.
async function cancelNoShows() {
  try {
    const n = await autoCancelNoShows();
    if (n) console.log(`[Tự huỷ] ${n} phiếu khám không đến đã chuyển sang Đã huỷ.`);
  } catch (err) {
    console.error('[Tự huỷ] Lỗi:', err.message);
  }
}
cancelNoShows();
setInterval(cancelNoShows, 60_000);
