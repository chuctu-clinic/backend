import { createApp } from './app.ts';
import { config } from './config.ts';
import { connectDb } from './db.ts';
import { seed } from './seed.ts';

const inMemory = await connectDb();
if (inMemory) {
  console.log('Chưa đặt MONGODB_URI: đang dùng MongoDB trong bộ nhớ (dữ liệu mất khi tắt server).');
  await seed({ demo: true });
}

createApp().listen(config.port, () => {
  console.log(`API Phòng khám Chúc Từ chạy tại http://localhost:${config.port}`);
});
