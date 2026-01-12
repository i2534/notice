#!/bin/bash
# Notice CLI Client 启动脚本
# 使用方法: ./start.sh <BROKER> <TOKEN> [TOPIC] [EXEC_CMD]
#
# 示例:
#   ./start.sh tcp://localhost:9091 your-token
#   ./start.sh wss://mqtt.example.com your-token notice/#
#   ./start.sh tcp://localhost:9091 your-token notice/# "./handler.sh"
#
# 环境变量:
#   CLIENT_ID - 客户端 ID (默认: cli-client-<hostname>)
#   EXEC_CMD  - 收到消息时执行的命令 (可选)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

BROKER="${1:-tcp://localhost:9091}"
TOKEN="${2}"
TOPIC="${3:-notice/#}"
EXEC_CMD="${4:-$EXEC_CMD}"
CLIENT_ID="${CLIENT_ID:-cli-client-$(hostname)}"

# 检测可执行文件
if [ -f "./notice-cli" ]; then
    CLI="./notice-cli"
    # 确保有执行权限
    chmod +x "$CLI" 2>/dev/null
else
    echo "错误: 未找到 notice-cli 可执行文件"
    exit 1
fi

if [ -z "$TOKEN" ]; then
    echo "使用方法: $0 <BROKER> <TOKEN> [TOPIC] [EXEC_CMD]"
    echo ""
    echo "示例:"
    echo "  $0 tcp://localhost:9091 your-token"
    echo "  $0 wss://mqtt.example.com your-token notice/#"
    echo "  $0 tcp://localhost:9091 your-token notice/# \"./handler.sh\""
    echo ""
    echo "环境变量:"
    echo "  CLIENT_ID - 客户端 ID"
    echo "  EXEC_CMD  - 收到消息时执行的命令"
    exit 1
fi

echo "启动 Notice CLI Client..."
echo "Broker: $BROKER"
echo "Topic:  $TOPIC"
[ -n "$EXEC_CMD" ] && echo "Exec:   $EXEC_CMD"
echo ""

if [ -n "$EXEC_CMD" ]; then
    exec "$CLI" -broker="$BROKER" -token="$TOKEN" -topic="$TOPIC" -id="$CLIENT_ID" -exec="$EXEC_CMD"
else
    exec "$CLI" -broker="$BROKER" -token="$TOKEN" -topic="$TOPIC" -id="$CLIENT_ID"
fi
