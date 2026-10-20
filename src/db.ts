import mongoose from 'mongoose';
import { config } from './config.ts';
import type { MongoMemoryReplSet } from 'mongodb-memory-server';

let memoryServer: MongoMemoryReplSet | null = null;

/**
 * Kết nối MongoDB. Không có MONGODB_URI (chỉ khi phát triển) thì chạy MongoDB trong bộ nhớ,
 * dạng replica set để giống Atlas. Trả về true nếu là CSDL trong bộ nhớ.
 */
export async function connectDb(uri = config.mongoUri) {
  let inMemory = false;
  if (!uri) {
    if (config.isProd) throw new Error('MONGODB_URI phải được đặt khi chạy production');
    const { MongoMemoryReplSet } = await import('mongodb-memory-server');
    memoryServer = await MongoMemoryReplSet.create({ replSet: { count: 1 } });
    uri = memoryServer.getUri('chuc-tu');
    inMemory = true;
  }
  mongoose.set('strictQuery', true);
  await mongoose.connect(uri);
  await mongoose.connection.syncIndexes();
  return inMemory;
}

export async function disconnectDb() {
  await mongoose.disconnect();
  if (memoryServer) {
    await memoryServer.stop();
    memoryServer = null;
  }
}
