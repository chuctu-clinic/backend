import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import cookieParser from 'cookie-parser';
import cors from 'cors';
import express from 'express';
import helmet from 'helmet';
import { config } from './config.js';
import { loadUser } from './middleware/auth.js';
import { errorHandler, notFoundHandler } from './middleware/error.js';
import adminRoutes from './routes/admin.routes.js';
import authRoutes from './routes/auth.routes.js';
import meRoutes from './routes/me.routes.js';
import publicRoutes from './routes/public.routes.js';
import staffRoutes from './routes/staff.routes.js';

export function createApp() {
  const app = express();
  app.set('trust proxy', 1);
  app.use(helmet({ contentSecurityPolicy: false, crossOriginResourcePolicy: { policy: 'cross-origin' } }));
  app.use(cors({ origin: config.clientOrigin, credentials: true }));
  app.use(express.json({ limit: '1mb' }));
  app.use(cookieParser());
  app.use(loadUser);

  app.get('/api/health', (req, res) => res.json({ ok: true }));
  app.use('/api', publicRoutes);
  app.use('/api/auth', authRoutes);
  app.use('/api/me', meRoutes);
  app.use('/api/staff', staffRoutes);
  app.use('/api/admin', adminRoutes);
  app.use('/api', notFoundHandler);

  // Khi đã build giao diện thì server phục vụ luôn, triển khai được thành một dịch vụ:
  // client/client-admin/dist ở /noi-bo/ (tiếp tân, Admin), client/client-users/dist ở / (khách)
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  const adminDist = path.join(root, 'client/client-admin/dist');
  const userDist = path.join(root, 'client/client-users/dist');
  app.get(['/quan-tri', '/quan-tri/*'], (req, res) => res.redirect(301, `/noi-bo${req.originalUrl}`)); // đường dẫn cũ
  if (fs.existsSync(adminDist)) {
    // Router không phân biệt dấu "/" cuối, nên tự kiểm tra để khỏi chuyển hướng vòng lặp
    app.get('/noi-bo', (req, res, next) => (req.originalUrl.split('?')[0] === '/noi-bo' ? res.redirect(301, req.originalUrl.replace('/noi-bo', '/noi-bo/')) : next()));
    app.use('/noi-bo', express.static(adminDist, { index: false, maxAge: '1h' }));
    app.get('/noi-bo/*', (req, res) => res.sendFile(path.join(adminDist, 'index.html')));
  }
  if (fs.existsSync(userDist)) {
    app.use(express.static(userDist, { index: false, maxAge: '1h' }));
    app.get('*', (req, res) => res.sendFile(path.join(userDist, 'index.html')));
  }

  app.use(errorHandler);
  return app;
}
