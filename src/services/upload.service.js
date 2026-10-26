import { Upload } from '../models/index.js';
import { badRequest, notFound } from '../utils/errors.js';

const TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];

export async function saveImage(admin, contentType, data) {
  const type = String(contentType || '').split(';')[0].trim();
  if (!TYPES.includes(type) || !Buffer.isBuffer(data) || data.length === 0) {
    throw badRequest('Chỉ nhận ảnh JPG, PNG, WEBP hoặc GIF, tối đa 3 MB.');
  }
  const img = await Upload.create({ contentType: type, size: data.length, data, createdBy: admin._id });
  return { id: img._id.toString(), url: `/api/uploads/${img._id}` };
}

export async function getImage(id) {
  const img = /^[a-f\d]{24}$/i.test(id) ? await Upload.findById(id) : null;
  if (!img) throw notFound('Không tìm thấy ảnh.');
  return img;
}
