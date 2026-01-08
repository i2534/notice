# 构建阶段
FROM golang:1.25-alpine AS builder

WORKDIR /app

# 安装 git (某些依赖可能需要)
RUN apk add --no-cache git

# 设置 Go 代理（国内加速）
ENV GOPROXY=https://goproxy.cn,direct

# 先复制依赖文件以利用缓存
COPY go.mod go.sum ./
RUN go mod download

# 复制源代码
COPY . .

# 构建静态链接的二进制文件（注入构建时间）
RUN CGO_ENABLED=0 GOOS=linux go build \
    -ldflags="-s -w -X 'main.BuildTime=$(date '+%Y-%m-%d %H:%M:%S')'" \
    -o notice-server

# 运行阶段
FROM alpine:3.20

WORKDIR /app

# 安装时区数据和 CA 证书
RUN apk add --no-cache ca-certificates tzdata

# 从构建阶段复制二进制文件
COPY --from=builder /app/notice-server .

# 创建日志目录
RUN mkdir -p /app/logs

# 设置环境变量默认值
ENV HTTP_PORT=9090 \
    MQTT_TCP_PORT=9091 \
    MQTT_WS_PORT=9092 \
    LOG_CONSOLE_LEVEL=info \
    LOG_FILE_LEVEL=debug \
    LOG_FILE_PATH=/app/logs/server.log \
    LOG_PRETTY=false

# 暴露端口
EXPOSE 9090 9091 9092

# 运行
ENTRYPOINT ["./notice-server"]

