import { createApp } from './app.js';
import { config } from './config.js';
import { connectDb } from './db.js';
import { seed } from './seed.js';

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
