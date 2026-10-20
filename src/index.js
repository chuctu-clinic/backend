import { createApp } from './app.js';
import { config } from './config.js';
import { connectDb } from './db.js';
import { seed } from './seed.js';

const inMemory = await connectDb();
if (inMemory) {
  console.log('Chưa đặt MONGODB_URI: đang dùng MongoDB trong bộ nhớ (dữ liệu mất khi tắt server).');
  await seed({ demo: true });
}

createApp().listen(config.port, () => {
  console.log(`API Phòng khám Chúc Từ chạy tại http://localhost:${config.port}`);
});
