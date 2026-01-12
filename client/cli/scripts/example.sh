#!/bin/bash
# Notice 消息处理示例脚本
#
# 用法:
#   使用环境变量: make run TOKEN=xxx EXEC="./scripts/example.sh"
#   使用 stdin:   make run TOKEN=xxx EXEC="./scripts/example.sh --stdin"
#
# 参数:
#   --stdin, -s    从 stdin 读取 JSON 消息 (需要 jq)
#   --env, -e      从环境变量读取消息 (默认)
#   --help, -h     显示帮助

set -e

# 日志文件
LOG_FILE="/tmp/notice-handler.log"

# 解析参数
MODE="env"
while [[ $# -gt 0 ]]; do
    case $1 in
        -s|--stdin)
            MODE="stdin"
            shift
            ;;
        -e|--env)
            MODE="env"
            shift
            ;;
        -h|--help)
            echo "Notice 消息处理示例脚本"
            echo ""
            echo "用法: $0 [选项]"
            echo ""
            echo "选项:"
            echo "  -s, --stdin   从 stdin 读取 JSON (需要 jq)"
            echo "  -e, --env     从环境变量读取 (默认)"
            echo "  -h, --help    显示帮助"
            echo ""
            echo "注意: topic 始终从环境变量 NOTICE_TOPIC 获取 (不在 JSON 消息体内)"
            echo ""
            echo "环境变量:"
            echo "  NOTICE_TOPIC      消息主题"
            echo "  NOTICE_TITLE      消息标题"
            echo "  NOTICE_CONTENT    消息内容"
            echo "  NOTICE_EXTRA      额外数据 (JSON)"
            echo "  NOTICE_TIMESTAMP  时间戳 (RFC3339)"
            echo "  NOTICE_RAW        原始 JSON"
            exit 0
            ;;
        *)
            echo "未知参数: $1"
            exit 1
            ;;
    esac
done

# 获取消息数据
# 注意: topic 始终从环境变量获取 (不在 JSON 消息体内)
TOPIC="$NOTICE_TOPIC"

if [ "$MODE" = "stdin" ]; then
    # 从 stdin 读取 JSON
    if ! command -v jq &> /dev/null; then
        echo "错误: 需要安装 jq 才能使用 --stdin 模式"
        exit 1
    fi
    
    JSON=$(cat)
    TITLE=$(echo "$JSON" | jq -r '.title // empty')
    CONTENT=$(echo "$JSON" | jq -r '.content // empty')
    EXTRA=$(echo "$JSON" | jq -c '.extra // empty')
    TIMESTAMP=$(echo "$JSON" | jq -r '.timestamp // empty')
    RAW="$JSON"
else
    # 从环境变量读取
    TITLE="$NOTICE_TITLE"
    CONTENT="$NOTICE_CONTENT"
    EXTRA="$NOTICE_EXTRA"
    TIMESTAMP="$NOTICE_TIMESTAMP"
    RAW="$NOTICE_RAW"
fi

# 记录时间
DATETIME=$(date '+%Y-%m-%d %H:%M:%S')

# 输出到日志
{
    echo "=========================================="
    echo "时间: $DATETIME"
    echo "模式: $MODE"
    echo "主题: $TOPIC"
    echo "标题: $TITLE"
    echo "内容: $CONTENT"
    echo "时间戳: $TIMESTAMP"
    if [ -n "$EXTRA" ] && [ "$EXTRA" != "null" ]; then
        echo "额外数据: $EXTRA"
    fi
    echo ""
} >> "$LOG_FILE"

# 输出到 stdout (会显示在客户端日志中)
echo "[$MODE] 已处理: $TITLE"

# ===== 以下是一些常见用例示例 =====

# 示例1: 语音播报 (需要安装 espeak)
# espeak -v zh "$TITLE"

# 示例2: 发送到 Telegram
# curl -s "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage" \
#     -d "chat_id=${CHAT_ID}" \
#     -d "text=${TITLE}: ${CONTENT}"

# 示例3: 发送邮件 (需要配置 sendmail)
# echo -e "Subject: $TITLE\n\n$CONTENT" | sendmail user@example.com

# 示例4: 调用其他 API
# curl -X POST "http://localhost:8080/webhook" \
#     -H "Content-Type: application/json" \
#     -d "$RAW"

# 示例5: 根据消息内容条件处理
# if echo "$TITLE" | grep -q "紧急"; then
#     notify-send -u critical "$TITLE" "$CONTENT"
# fi

exit 0
