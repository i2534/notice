.PHONY: build build-release docker docker-release docker-rebuild docker-clean clean help

# 默认目标
help:
	@echo "Android Notice Client 构建命令:"
	@echo ""
	@echo "  make build          - 构建 Debug APK (需要本地 Android SDK)"
	@echo "  make build-release  - 构建 Release APK"
	@echo "  make docker         - 使用 Docker 构建 Debug APK"
	@echo "  make docker-release - 使用 Docker 构建 Release APK"
	@echo "  make docker-rebuild - 强制重新构建 Docker 镜像"
	@echo "  make docker-clean   - 清理 Docker 镜像"
	@echo "  make clean          - 清理构建产物"
	@echo ""

# 本地构建 (需要 Android SDK)
build:
	./gradlew assembleDebug
	@echo ""
	@echo "✅ Debug APK 已生成:"
	@ls -lh app/build/outputs/apk/debug/*.apk

build-release:
	./gradlew assembleRelease
	@echo ""
	@echo "✅ Release APK 已生成:"
	@ls -lh app/build/outputs/apk/release/*.apk 2>/dev/null || echo "⚠️  需要配置签名"

# 缓存目录 (加速后续构建)
GRADLE_CACHE ?= $(PWD)/.gradle-cache
PROJECT_GRADLE ?= $(PWD)/.gradle
ANDROID_HOME_CACHE ?= $(PWD)/.android
KOTLIN_CACHE ?= $(PWD)/.kotlin

# Docker 构建 (无需本地 Android SDK)
docker:
	@echo "🐳 使用 Docker 构建..."
	@if docker image inspect notice-android-builder >/dev/null 2>&1; then \
		echo "✓ 镜像已存在，跳过构建"; \
	else \
		echo "→ 构建镜像..."; \
		docker build -t notice-android-builder .; \
	fi
	@mkdir -p $(GRADLE_CACHE) $(PROJECT_GRADLE) $(ANDROID_HOME_CACHE) $(KOTLIN_CACHE)
	@echo ""
	@echo "📦 构建 APK (源码+缓存挂载)..."
	docker run --rm \
		-u $(shell id -u):$(shell id -g) \
		-e ANDROID_USER_HOME=/android-home \
		-v "$(PWD)/app:/app/app" \
		-v "$(GRADLE_CACHE):/gradle-cache" \
		-v "$(PROJECT_GRADLE):/app/.gradle" \
		-v "$(ANDROID_HOME_CACHE):/android-home" \
		-v "$(KOTLIN_CACHE):/app/.kotlin" \
		notice-android-builder
	@echo ""
	@echo "✅ Debug APK 已生成:"
	@ls -lh app/build/outputs/apk/debug/*.apk

# 签名配置 (通过环境变量传入)
KEYSTORE_PASSWORD ?= abcdef
KEY_PASSWORD ?= $(KEYSTORE_PASSWORD)
KEY_ALIAS ?= notice

# Docker 构建 Release APK
docker-release:
	@echo "🐳 使用 Docker 构建 Release..."
	@if [ -z "$(KEYSTORE_PASSWORD)" ]; then \
		echo "❌ 请设置签名密码:"; \
		echo "   make docker-release KEYSTORE_PASSWORD=xxx"; \
		echo "   (KEY_PASSWORD 默认与 KEYSTORE_PASSWORD 一致)"; \
		exit 1; \
	fi
	@if docker image inspect notice-android-builder >/dev/null 2>&1; then \
		echo "✓ 镜像已存在，跳过构建"; \
	else \
		echo "→ 构建镜像..."; \
		docker build -t notice-android-builder .; \
	fi
	@mkdir -p $(GRADLE_CACHE) $(PROJECT_GRADLE) $(ANDROID_HOME_CACHE) $(KOTLIN_CACHE)
	@echo ""
	@echo "📦 构建 Release APK..."
	docker run --rm \
		-u $(shell id -u):$(shell id -g) \
		-e ANDROID_USER_HOME=/android-home \
		-e KEYSTORE_PASSWORD="$(KEYSTORE_PASSWORD)" \
		-e KEY_PASSWORD="$(KEY_PASSWORD)" \
		-e KEY_ALIAS="$(KEY_ALIAS)" \
		-v "$(PWD)/app:/app/app" \
		-v "$(PWD)/release.keystore:/app/release.keystore:ro" \
		-v "$(GRADLE_CACHE):/gradle-cache" \
		-v "$(PROJECT_GRADLE):/app/.gradle" \
		-v "$(ANDROID_HOME_CACHE):/android-home" \
		-v "$(KOTLIN_CACHE):/app/.kotlin" \
		notice-android-builder \
		./gradlew assembleRelease --no-daemon --console=plain
	@echo ""
	@echo "✅ Release APK 已生成:"
	@ls -lh app/build/outputs/apk/release/*.apk

# 强制重新构建 Docker 镜像 (不使用缓存)
docker-rebuild:
	@echo "🔨 强制重新构建镜像 (--no-cache)..."
	docker build --no-cache -t notice-android-builder .
	@echo "✅ 镜像重建完成"

# 清理
clean:
	./gradlew clean 2>/dev/null || true
	rm -rf app/build
	rm -f app-debug.apk
	@echo "✅ 清理完成"

# 清理 Docker 镜像
docker-clean:
	docker rmi notice-android-builder 2>/dev/null || true
	@echo "✅ Docker 镜像已清理"
