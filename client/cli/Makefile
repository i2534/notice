.PHONY: run run-example build build-windows build-linux build-all clean help stop

# 配置
BROKER ?= tcp://localhost:9091
TOPIC ?= notice/\#
CLIENT_ID ?= cli-client
TOKEN ?= abcdef
EXEC ?=
MODE ?= env

# 版本信息
VERSION ?= dev
BUILD_TIME := $(shell date '+%Y-%m-%d %H:%M:%S')
LDFLAGS := -s -w -X 'main.Version=$(VERSION)' -X 'main.BuildTime=$(BUILD_TIME)'

# 输出文件名
BINARY_NAME := notice-cli
BUILD_DIR := build

# 默认目标
help:
	@echo "Notice CLI Client Makefile"
	@echo ""
	@echo "使用方法:"
	@echo "  make run           - 运行客户端（接收通知）"
	@echo "  make run-example   - 运行客户端并使用示例脚本处理消息"
	@echo "  make build         - 构建当前平台可执行文件"
	@echo "  make build-linux   - 构建 Linux 版本"
	@echo "  make build-windows - 构建 Windows 版本"
	@echo "  make build-all     - 构建所有平台版本"
	@echo "  make clean         - 清理构建文件"
	@echo ""
	@echo "自定义配置:"
	@echo "  make run BROKER=tcp://192.168.1.100:9091  # TCP 直连"
	@echo "  make run BROKER=ws://localhost:9092       # WebSocket"
	@echo "  make run BROKER=wss://notice.example.com  # WebSocket + TLS"
	@echo "  make run TOPIC=custom/#"
	@echo "  make run TOKEN=your-secret-token          # 带认证"
	@echo "  make run EXEC='bash ./scripts/example.sh' # 收到消息时执行命令"
	@echo ""
	@echo "示例脚本用法:"
	@echo "  make run-example TOKEN=xxx               # 使用环境变量模式"
	@echo "  make run-example TOKEN=xxx MODE=stdin    # 使用 stdin 模式"

# 运行（开发模式）
run:
	@go build -o /tmp/notice-client-dev main.go && \
	exec /tmp/notice-client-dev -broker=$(BROKER) -topic=$(TOPIC) -id=$(CLIENT_ID) $(if $(TOKEN),-token=$(TOKEN),) $(if $(EXEC),-exec=$(EXEC),)

# 运行并使用示例脚本处理消息
# MODE: env (默认) 或 stdin
run-example:
	@go build -o /tmp/notice-client-dev main.go && \
	exec /tmp/notice-client-dev -broker=$(BROKER) -topic=$(TOPIC) -id=$(CLIENT_ID) $(if $(TOKEN),-token=$(TOKEN),) -exec="bash ./scripts/example.sh $(if $(filter stdin,$(MODE)),--stdin,--env)"

# 构建当前平台
build:
	@mkdir -p $(BUILD_DIR)
	@echo "Building $(BINARY_NAME) $(VERSION) for current platform..."
	go build -ldflags="$(LDFLAGS)" -o $(BUILD_DIR)/$(BINARY_NAME)
	@echo "Done: $(BUILD_DIR)/$(BINARY_NAME) --version"

# 构建 Linux 版本 (amd64)
build-linux:
	@mkdir -p $(BUILD_DIR)
	@echo "Building $(BINARY_NAME) $(VERSION) for Linux (amd64)..."
	GOOS=linux GOARCH=amd64 go build -ldflags="$(LDFLAGS)" -o $(BUILD_DIR)/$(BINARY_NAME)-linux-amd64
	@echo "Done: $(BUILD_DIR)/$(BINARY_NAME)-linux-amd64"

# 构建 Linux ARM64 版本
build-linux-arm64:
	@mkdir -p $(BUILD_DIR)
	@echo "Building $(BINARY_NAME) $(VERSION) for Linux (arm64)..."
	GOOS=linux GOARCH=arm64 go build -ldflags="$(LDFLAGS)" -o $(BUILD_DIR)/$(BINARY_NAME)-linux-arm64
	@echo "Done: $(BUILD_DIR)/$(BINARY_NAME)-linux-arm64"

# 构建 Windows 版本 (amd64)
build-windows:
	@mkdir -p $(BUILD_DIR)
	@echo "Building $(BINARY_NAME) $(VERSION) for Windows (amd64)..."
	GOOS=windows GOARCH=amd64 go build -ldflags="$(LDFLAGS)" -o $(BUILD_DIR)/$(BINARY_NAME)-windows-amd64.exe
	@echo "Done: $(BUILD_DIR)/$(BINARY_NAME)-windows-amd64.exe"

# 构建 macOS 版本 (amd64)
build-darwin:
	@mkdir -p $(BUILD_DIR)
	@echo "Building $(BINARY_NAME) $(VERSION) for macOS (amd64)..."
	GOOS=darwin GOARCH=amd64 go build -ldflags="$(LDFLAGS)" -o $(BUILD_DIR)/$(BINARY_NAME)-darwin-amd64
	@echo "Done: $(BUILD_DIR)/$(BINARY_NAME)-darwin-amd64"

# 构建 macOS ARM64 版本 (Apple Silicon)
build-darwin-arm64:
	@mkdir -p $(BUILD_DIR)
	@echo "Building $(BINARY_NAME) $(VERSION) for macOS (arm64)..."
	GOOS=darwin GOARCH=arm64 go build -ldflags="$(LDFLAGS)" -o $(BUILD_DIR)/$(BINARY_NAME)-darwin-arm64
	@echo "Done: $(BUILD_DIR)/$(BINARY_NAME)-darwin-arm64"

# 构建所有平台版本
build-all: build-linux build-linux-arm64 build-windows build-darwin build-darwin-arm64
	@echo ""
	@echo "All builds complete:"
	@ls -la $(BUILD_DIR)/

# 停止客户端
stop:
	@pkill -f "notice-client.*-id=$(CLIENT_ID)" 2>/dev/null && echo "客户端已停止" || echo "客户端未运行"

# 清理
clean:
	rm -rf $(BUILD_DIR)
