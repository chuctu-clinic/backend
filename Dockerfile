# API Phòng khám Chúc Từ:
#   docker build -t chuctu-backend .
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production

# Cài thư viện trước để Docker dùng lại lớp cache khi chỉ sửa mã nguồn
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force

COPY src src

EXPOSE 4000
# Nạp tài khoản Admin, Tiếp tân và 4 trang thông tin nếu chưa có (chạy lại không sao), rồi chạy API
CMD ["sh", "-c", "node src/seed.js && node src/index.js"]
