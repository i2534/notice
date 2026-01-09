.PHONY: run build test-push test-push-extra clean help docker-build docker-run docker-stop

# 配置文件路径（可覆盖）
CONFIG ?= config.yaml

# 测试用配置
HTTP_PORT ?= 9090
MQTT_TCP_PORT ?= 9091
AUTH_TOKEN ?= abcdef

# 默认目标
help:
	@echo "Notice Server Makefile"
	@echo ""
	@echo "使用方法:"
	@echo "  make run             - 运行服务器（使用 config.yaml）"
	@echo "  make run-console     - 运行服务器（仅控制台输出）"
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
	@echo "配置文件:"
	@echo "  make run                            # 使用默认 config.yaml"
	@echo "  make run CONFIG=my-config.yaml      # 指定配置文件"
	@echo ""
	@echo "快速测试:"
	@echo "  make run AUTH_TOKEN=your-token      # 覆盖 Token"

# 运行服务器（使用配置文件）
run:
	@if [ -f "$(CONFIG)" ]; then \
		AUTH_TOKEN=$(AUTH_TOKEN) go run main.go -c $(CONFIG); \
	else \
		echo "配置文件 $(CONFIG) 不存在，使用默认配置"; \
		AUTH_TOKEN=$(AUTH_TOKEN) go run main.go; \
	fi

# 运行服务器（仅控制台输出，不写日志文件）
run-console:
	AUTH_TOKEN=$(AUTH_TOKEN) LOG_FILE_PATH= go run main.go -c $(CONFIG)

# 运行服务器（debug 模式）
run-debug:
	AUTH_TOKEN=$(AUTH_TOKEN) LOG_CONSOLE_LEVEL=debug go run main.go -c $(CONFIG)

# 版本信息
VERSION ?= dev
BUILD_TIME := $(shell date '+%Y-%m-%d %H:%M:%S')
LDFLAGS := -s -w -X 'main.Version=$(VERSION)' -X 'main.BuildTime=$(BUILD_TIME)'

# 构建
build:
	@echo "Building notice-server $(VERSION)..."
	go build -ldflags="$(LDFLAGS)" -o notice-server
	@echo "Done: ./notice-server --version"

# 发送测试通知（带时间戳）
test-push:
	@TIME=$$(date '+%H:%M:%S'); \
	curl -X POST http://localhost:$(HTTP_PORT)/webhook \
		-H "Content-Type: application/json" \
		-H "Authorization: Bearer $(AUTH_TOKEN)" \
		-d "{\"title\":\"测试通知 [$$TIME]\",\"content\":\"这是一条测试消息\\n发送时间: $$TIME\"}"

# 发送带额外数据的测试通知（带时间戳和随机订单号）
test-push-extra:
	@TIME=$$(date '+%H:%M:%S'); \
	ORDER_ID="ORD-$$(date '+%Y%m%d')-$$RANDOM"; \
	curl -X POST http://localhost:$(HTTP_PORT)/webhook \
		-H "Content-Type: application/json" \
		-H "Authorization: Bearer $(AUTH_TOKEN)" \
		-d "{\"title\":\"订单通知 [$$TIME]\",\"content\":\"您的订单已发货\\n订单号: $$ORDER_ID\",\"extra\":{\"orderId\":\"$$ORDER_ID\",\"status\":\"shipped\",\"time\":\"$$TIME\"}}"

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

# 查看消息历史
messages:
	@curl -s "http://localhost:$(HTTP_PORT)/messages?page=1&page_size=10" \
		-H "Authorization: Bearer $(AUTH_TOKEN)" | jq .

# 查看日志
logs:
	@LOG_PATH=$$(grep -oP 'file_path:\s*"\K[^"]+' $(CONFIG) 2>/dev/null || echo "logs/server.log"); \
	if [ -f "$$LOG_PATH" ]; then \
		tail -f "$$LOG_PATH"; \
	else \
		echo "日志文件不存在: $$LOG_PATH"; \
	fi

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

# 运行 Docker 容器（挂载配置文件）
docker-run:
	@if [ -f "$(CONFIG)" ]; then \
		docker run -d --name notice-server \
			-p $(HTTP_PORT):9090 \
			-p $(MQTT_TCP_PORT):9091 \
			-p 9092:9092 \
			-v $(PWD)/$(CONFIG):/app/config.yaml:ro \
			$(DOCKER_IMAGE):$(DOCKER_TAG); \
	else \
		echo "配置文件 $(CONFIG) 不存在"; \
		echo "创建配置: cp config.example.yaml config.yaml"; \
		exit 1; \
	fi

# 停止并删除容器
docker-stop:
	docker stop notice-server || true
	docker rm notice-server || true
