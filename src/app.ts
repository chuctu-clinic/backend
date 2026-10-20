import cookieParser from 'cookie-parser';
import cors from 'cors';
import express from 'express';
import helmet from 'helmet';
import { config } from './config.ts';
import { loadUser } from './middleware/auth.ts';
import { errorHandler, notFoundHandler } from './middleware/error.ts';
import adminRoutes from './routes/admin.routes.ts';
import authRoutes from './routes/auth.routes.ts';
import meRoutes from './routes/me.routes.ts';
import publicRoutes from './routes/public.routes.ts';
import staffRoutes from './routes/staff.routes.ts';

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

  app.use(errorHandler);
  return app;
}
