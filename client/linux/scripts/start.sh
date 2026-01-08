#!/bin/bash
# Notice Client 启动脚本
# 使用方法: ./start.sh <BROKER> <TOKEN> [TOPIC]
#
# 示例:
#   ./start.sh tcp://localhost:9091 your-token
#   ./start.sh wss://mqtt.example.com your-token notice/#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

BROKER="${1:-tcp://localhost:9091}"
TOKEN="${2}"
TOPIC="${3:-notice/#}"
CLIENT_ID="${CLIENT_ID:-linux-client-$(hostname)}"

# 检测可执行文件
if [ -f "./notice-client" ]; then
    EXEC="./notice-client"
else
    echo "错误: 未找到 notice-client 可执行文件"
    exit 1
fi

if [ -z "$TOKEN" ]; then
    echo "使用方法: $0 <BROKER> <TOKEN> [TOPIC]"
    echo ""
    echo "示例:"
    echo "  $0 tcp://localhost:9091 your-token"
    echo "  $0 wss://mqtt.example.com your-token notice/#"
    exit 1
fi

echo "启动 Notice Client..."
echo "Broker: $BROKER"
echo "Topic:  $TOPIC"
echo ""

exec "$EXEC" -broker="$BROKER" -token="$TOKEN" -topic="$TOPIC" -id="$CLIENT_ID"
