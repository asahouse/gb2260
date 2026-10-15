# ==========================================
# GB2260 全国行政区划采集服务 Dockerfile
# 基于轻量级 OpenJDK 8 JRE Alpine 镜像构建
# ==========================================
FROM openjdk:8-jre-alpine

LABEL maintainer="BenjaminKC <asahouse@163.com>"
LABEL description="GB2260 全国行政区划高并发采集、断点自愈与级联服务"

# 设置系统时区为亚洲/上海
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

WORKDIR /app

# 拷贝构建好的 Spring Boot 胖 Jar 包
COPY target/gb2260-0.0.1-SNAPSHOT.jar app.jar

# 暴露服务端口 (Web 控制台、Swagger 与 REST API)
EXPOSE 8080

# 默认 JVM 参数与环境变量预设 (支持 docker run -e 或 docker-compose 覆盖)
ENV JAVA_OPTS="-Xms512m -Xmx2048m -Dfile.encoding=UTF-8"
ENV DB_HOST="localhost"
ENV DB_PORT="3306"
ENV DB_USERNAME="root"
ENV DB_PASSWORD=""
ENV REDIS_HOST="localhost"
ENV REDIS_PORT="6379"
ENV REDIS_PASSWORD=""
ENV GB2260_SOURCE="mca"
ENV MCA_OFFLINE_FIRST="true"

# 启动容器时运行 Spring Boot 应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
