.PHONY: run build clean help

# 配置
BROKER ?= tcp://localhost:9091
TOPIC ?= notice/\#
CLIENT_ID ?= linux-client
TOKEN ?=

# 默认目标
help:
	@echo "Notice Client (Linux) Makefile"
	@echo ""
	@echo "使用方法:"
	@echo "  make run       - 运行客户端（接收通知）"
	@echo "  make build     - 构建可执行文件"
	@echo "  make clean     - 清理构建文件"
	@echo ""
	@echo "自定义配置:"
	@echo "  make run BROKER=tcp://192.168.1.100:9091  # TCP 直连"
	@echo "  make run BROKER=ws://localhost:9092       # WebSocket"
	@echo "  make run BROKER=wss://notice.example.com  # WebSocket + TLS"
	@echo "  make run TOPIC=custom/#"
	@echo "  make run TOKEN=your-secret-token         # 带认证"

# 运行
run:
	go run main.go -broker=$(BROKER) -topic=$(TOPIC) -id=$(CLIENT_ID) $(if $(TOKEN),-token=$(TOKEN),)

# 构建
build:
	go build -o notice-client

# 清理
clean:
	rm -f notice-client
