.PHONY: help dev build install-deps clean docker-build docker-build-all

# 版本号
VERSION ?= 0.1.0

# 输出目录
OUTPUT_DIR ?= dist

# 默认目标
help:
	@echo "Notice Client (GUI) Makefile"
	@echo ""
	@echo "本地开发:"
	@echo "  make install-deps  - 安装开发依赖 (需要 sudo)"
	@echo "  make dev           - 开发模式运行"
	@echo "  make build         - 构建发布版本"
	@echo "  make clean         - 清理构建文件"
	@echo ""
	@echo "Docker 构建:"
	@echo "  make docker-build         - 使用 Docker 构建 Linux 版本"
	@echo "  make docker-build-all     - 构建所有平台版本"
	@echo ""
	@echo "参数:"
	@echo "  VERSION=x.x.x     - 指定版本号 (默认: $(VERSION))"

# 安装 Linux 开发依赖
install-deps:
	@echo "安装 Tauri 开发依赖..."
	sudo apt-get update
	sudo apt-get install -y \
		libgtk-3-dev \
		libwebkit2gtk-4.1-dev \
		libayatana-appindicator3-dev \
		librsvg2-dev
	@echo "依赖安装完成"

# 开发模式
dev:
	npm run dev

# 构建发布版本
build:
	npm run build

# 使用 Docker 构建 Linux 版本
docker-build:
	@echo "使用 Docker 构建 Linux 版本..."
	@mkdir -p $(OUTPUT_DIR)
	docker build \
		--build-arg VERSION=$(VERSION) \
		--target export \
		--output type=local,dest=$(OUTPUT_DIR) \
		-f Dockerfile .
	@echo "构建完成: $(OUTPUT_DIR)/notice-gui-linux-amd64.zip"

# 构建所有平台 (Linux amd64 + arm64)
docker-build-all: docker-build-amd64 docker-build-arm64
	@echo "所有平台构建完成"

# Linux amd64
docker-build-amd64:
	@echo "构建 Linux amd64..."
	@mkdir -p $(OUTPUT_DIR)/linux-amd64
	docker build \
		--platform linux/amd64 \
		--build-arg VERSION=$(VERSION) \
		--target export \
		--output type=local,dest=$(OUTPUT_DIR)/linux-amd64 \
		-f Dockerfile .

# Linux arm64
docker-build-arm64:
	@echo "构建 Linux arm64..."
	@mkdir -p $(OUTPUT_DIR)/linux-arm64
	docker build \
		--platform linux/arm64 \
		--build-arg VERSION=$(VERSION) \
		--target export \
		--output type=local,dest=$(OUTPUT_DIR)/linux-arm64 \
		-f Dockerfile .

# 清理
clean:
	rm -rf tauri/target
	rm -rf node_modules
	rm -rf $(OUTPUT_DIR)
