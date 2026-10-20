# API Phòng khám Chúc Từ. Build từ thư mục gốc dự án (cần shared/ và package-lock.json):
#   docker build -f server/Dockerfile .
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production

# Cài thư viện trước để Docker dùng lại lớp cache khi chỉ sửa mã nguồn
COPY package.json package-lock.json ./
COPY shared/package.json shared/
COPY server/package.json server/
COPY client/client-users/package.json client/client-users/
COPY client/client-admin/package.json client/client-admin/
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force

COPY shared shared
COPY server server

WORKDIR /app/server
EXPOSE 4000
# Nạp tài khoản Admin, Tiếp tân và 4 trang thông tin nếu chưa có (chạy lại không sao), rồi chạy API
CMD ["sh", "-c", "node src/seed.js && node src/index.js"]
