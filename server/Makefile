.PHONY: run build test-push test-push-extra clean help docker-build docker-run docker-stop

# 配置
HTTP_PORT ?= 9090
MQTT_TCP_PORT ?= 9091
MQTT_WS_PORT ?= 9092

# 认证配置（为空则自动生成）
AUTH_TOKEN ?= abcdef

# 限流配置
RATE_LIMIT_MAX_FAILURES ?= 5
RATE_LIMIT_BLOCK_TIME ?= 900
RATE_LIMIT_WINDOW_TIME ?= 300

# 日志配置
LOG_CONSOLE_LEVEL ?= info
LOG_FILE_LEVEL ?= debug
LOG_FILE_PATH ?= logs/server.log
LOG_PRETTY ?= true
LOG_ROTATE_DAYS ?= 1
LOG_MAX_FILES ?= 7

# 默认目标
help:
	@echo "Notice Server Makefile"
	@echo ""
	@echo "服务端口:"
	@echo "  HTTP Webhook:   $(HTTP_PORT)"
	@echo "  MQTT TCP:       $(MQTT_TCP_PORT)"
	@echo "  MQTT WebSocket: $(MQTT_WS_PORT)"
	@echo ""
	@echo "认证配置:"
	@echo "  AUTH_TOKEN:     $(if $(AUTH_TOKEN),$(shell echo $(AUTH_TOKEN) | head -c 4)****,未启用)"
	@echo ""
	@echo "日志配置:"
	@echo "  Console Level:  $(LOG_CONSOLE_LEVEL)"
	@echo "  File Level:     $(LOG_FILE_LEVEL)"
	@echo "  File Path:      $(LOG_FILE_PATH)"
	@echo "  Rotate Days:    $(LOG_ROTATE_DAYS)"
	@echo "  Max Files:      $(LOG_MAX_FILES)"
	@echo ""
	@echo "使用方法:"
	@echo "  make run             - 运行服务器（写日志文件）"
	@echo "  make run-console     - 运行服务器（仅 console）"
	@echo "  make run-debug       - 运行服务器（debug 模式）"
	@echo "  make build           - 构建可执行文件"
	@echo "  make test-push       - 发送测试通知"
	@echo "  make test-push-extra - 发送带额外数据的测试通知"
	@echo "  make test-sub        - 订阅消息（需要 mosquitto-clients）"
	@echo "  make status          - 查看服务状态"
	@echo "  make logs            - 查看日志文件"
	@echo "  make clean           - 清理构建文件"
	@echo ""
	@echo "Docker 命令:"
	@echo "  make docker-build    - 构建 Docker 镜像"
	@echo "  make docker-run      - 运行 Docker 容器"
	@echo "  make docker-stop     - 停止并删除容器"
	@echo ""
	@echo "启用认证:"
	@echo "  make run AUTH_TOKEN=your-secret-token"

# 运行服务器
run:
	HTTP_PORT=$(HTTP_PORT) \
	MQTT_TCP_PORT=$(MQTT_TCP_PORT) \
	MQTT_WS_PORT=$(MQTT_WS_PORT) \
	AUTH_TOKEN=$(AUTH_TOKEN) \
	RATE_LIMIT_MAX_FAILURES=$(RATE_LIMIT_MAX_FAILURES) \
	RATE_LIMIT_BLOCK_TIME=$(RATE_LIMIT_BLOCK_TIME) \
	RATE_LIMIT_WINDOW_TIME=$(RATE_LIMIT_WINDOW_TIME) \
	LOG_CONSOLE_LEVEL=$(LOG_CONSOLE_LEVEL) \
	LOG_FILE_LEVEL=$(LOG_FILE_LEVEL) \
	LOG_FILE_PATH=$(LOG_FILE_PATH) \
	LOG_PRETTY=$(LOG_PRETTY) \
	LOG_ROTATE_DAYS=$(LOG_ROTATE_DAYS) \
	LOG_MAX_FILES=$(LOG_MAX_FILES) \
	go run main.go

# 运行服务器（仅 console，不写文件）
run-console:
	HTTP_PORT=$(HTTP_PORT) \
	MQTT_TCP_PORT=$(MQTT_TCP_PORT) \
	MQTT_WS_PORT=$(MQTT_WS_PORT) \
	AUTH_TOKEN=$(AUTH_TOKEN) \
	RATE_LIMIT_MAX_FAILURES=$(RATE_LIMIT_MAX_FAILURES) \
	RATE_LIMIT_BLOCK_TIME=$(RATE_LIMIT_BLOCK_TIME) \
	RATE_LIMIT_WINDOW_TIME=$(RATE_LIMIT_WINDOW_TIME) \
	LOG_CONSOLE_LEVEL=$(LOG_CONSOLE_LEVEL) \
	LOG_PRETTY=$(LOG_PRETTY) \
	go run main.go

# 运行服务器（debug 模式）
run-debug:
	HTTP_PORT=$(HTTP_PORT) \
	MQTT_TCP_PORT=$(MQTT_TCP_PORT) \
	MQTT_WS_PORT=$(MQTT_WS_PORT) \
	AUTH_TOKEN=$(AUTH_TOKEN) \
	RATE_LIMIT_MAX_FAILURES=$(RATE_LIMIT_MAX_FAILURES) \
	RATE_LIMIT_BLOCK_TIME=$(RATE_LIMIT_BLOCK_TIME) \
	RATE_LIMIT_WINDOW_TIME=$(RATE_LIMIT_WINDOW_TIME) \
	LOG_CONSOLE_LEVEL=debug \
	LOG_FILE_LEVEL=debug \
	LOG_FILE_PATH=$(LOG_FILE_PATH) \
	LOG_PRETTY=$(LOG_PRETTY) \
	LOG_ROTATE_DAYS=$(LOG_ROTATE_DAYS) \
	LOG_MAX_FILES=$(LOG_MAX_FILES) \
	go run main.go

# 构建时间
BUILD_TIME := $(shell date '+%Y-%m-%d %H:%M:%S')
LDFLAGS := -s -w -X 'main.BuildTime=$(BUILD_TIME)'

# 构建
build:
	go build -ldflags="$(LDFLAGS)" -o notice-server

# 发送简单测试通知
test-push:
	curl -X POST http://localhost:$(HTTP_PORT)/webhook \
		-H "Content-Type: application/json" \
		$(if $(AUTH_TOKEN),-H "Authorization: Bearer $(AUTH_TOKEN)",) \
		-d '{"title":"测试通知","content":"这是一条测试消息"}'

# 发送带额外数据的测试通知
test-push-extra:
	curl -X POST http://localhost:$(HTTP_PORT)/webhook \
		-H "Content-Type: application/json" \
		$(if $(AUTH_TOKEN),-H "Authorization: Bearer $(AUTH_TOKEN)",) \
		-d '{"title":"订单通知","content":"您的订单已发货","extra":{"orderId":"ORD-12345","status":"shipped"}}'

# 订阅消息（需要安装 mosquitto-clients）
test-sub:
	mosquitto_sub -h localhost -p $(MQTT_TCP_PORT) -t "notice/#" -v \
		$(if $(AUTH_TOKEN),-u "$(AUTH_TOKEN)",)

# 健康检查
health:
	curl -s http://localhost:$(HTTP_PORT)/health

# 状态检查
status:
	curl -s http://localhost:$(HTTP_PORT)/status

# 查看日志
logs:
	tail -f $(LOG_FILE_PATH)

# 清理
clean:
	rm -f notice-server
	rm -rf logs/

# Docker 镜像名称
DOCKER_IMAGE ?= notice-server
DOCKER_TAG ?= latest

# 构建 Docker 镜像
docker-build:
	docker build -t $(DOCKER_IMAGE):$(DOCKER_TAG) .

# 运行 Docker 容器
docker-run:
	docker run -d --name notice-server \
		-p $(HTTP_PORT):9090 \
		-p $(MQTT_TCP_PORT):9091 \
		-p $(MQTT_WS_PORT):9092 \
		-e AUTH_TOKEN=$(AUTH_TOKEN) \
		-e RATE_LIMIT_MAX_FAILURES=$(RATE_LIMIT_MAX_FAILURES) \
		-e RATE_LIMIT_BLOCK_TIME=$(RATE_LIMIT_BLOCK_TIME) \
		-e RATE_LIMIT_WINDOW_TIME=$(RATE_LIMIT_WINDOW_TIME) \
		$(DOCKER_IMAGE):$(DOCKER_TAG)

# 停止并删除容器
docker-stop:
	docker stop notice-server || true
	docker rm notice-server || true