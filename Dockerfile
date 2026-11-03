# API Phòng khám Chúc Từ (Spring Boot + MySQL):
#   docker build -t chuctu-backend .
# Tầng build: JDK 21 + Maven Wrapper. Tầng chạy: JRE 21, không chạy bằng root.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Tải thư viện trước để Docker dùng lại lớp cache khi chỉ sửa mã nguồn
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
# Kiểm thử cần MySQL thật (chuctu_test) nên không chạy ở bước build ảnh
RUN ./mvnw -q -B package -DskipTests && cp target/clinic-*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV NODE_ENV=production
RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /build/app.jar app.jar
USER app

EXPOSE 4000
# Flyway tạo bảng, rồi server nạp tài khoản Admin, Tiếp tân và 4 trang thông tin nếu chưa có (chạy lại không sao), rồi mở cổng
ENTRYPOINT ["java", "-jar", "app.jar"]
