#!/bin/bash
# Notice Server 启动脚本
# 使用方法:
#   ./start.sh [选项] [AUTH_TOKEN]
#
# 选项:
#   -d, --daemon    后台运行
#   -s, --stop      停止服务
#   -r, --restart   重启服务
#   --status        查看状态
#   -h, --help      显示帮助

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PID_FILE="./notice-server.pid"
DEFAULT_LOG_FILE="./logs/server.log"

show_help() {
    echo "Notice Server 启动脚本"
    echo ""
    echo "使用方法: $0 [选项] [AUTH_TOKEN]"
    echo ""
    echo "选项:"
    echo "  -d, --daemon    后台运行"
    echo "  -s, --stop      停止服务"
    echo "  -r, --restart   重启服务"
    echo "  --status        查看运行状态"
    echo "  -h, --help      显示此帮助"
    echo ""
    echo "环境变量配置:"
    echo ""
    echo "  基础配置:"
    echo "    HTTP_PORT             HTTP 服务端口 (默认: 9090)"
    echo "    MQTT_TCP_PORT         MQTT TCP 端口 (默认: 9091)"
    echo "    MQTT_WS_PORT          MQTT WebSocket 端口 (默认: 9092)"
    echo "    MQTT_TOPIC            默认消息主题 (默认: notice)"
    echo ""
    echo "  认证配置:"
    echo "    AUTH_TOKEN            访问令牌 (不设置则自动生成)"
    echo ""
    echo "  限流配置:"
    echo "    RATE_LIMIT_MAX_FAILURES   最大失败次数 (默认: 5)"
    echo "    RATE_LIMIT_BLOCK_TIME     封禁时间/秒 (默认: 900)"
    echo "    RATE_LIMIT_WINDOW_TIME    统计窗口/秒 (默认: 300)"
    echo ""
    echo "  日志配置:"
    echo "    LOG_CONSOLE_LEVEL     控制台日志级别: debug/info/warn/error/off (默认: info)"
    echo "    LOG_FILE_LEVEL        文件日志级别: debug/info/warn/error/off (默认: debug)"
    echo "    LOG_FILE_PATH         日志文件路径 (默认: 空，后台运行时: $DEFAULT_LOG_FILE)"
    echo "    LOG_PRETTY            控制台美化输出: true/false (默认: true)"
    echo "    LOG_ROTATE_DAYS       日志轮转天数 (默认: 1)"
    echo "    LOG_MAX_FILES         保留日志文件数 (默认: 7)"
    echo ""
    echo "  MQTT 会话配置:"
    echo "    MQTT_SESSION_EXPIRY   会话过期时间/秒 (默认: 3600)"
    echo "    MQTT_MESSAGE_EXPIRY   消息过期时间/秒 (默认: 86400)"
    echo ""
    echo "示例:"
    echo "  $0                                    # 前台运行"
    echo "  $0 your-token                         # 前台运行，指定 TOKEN"
    echo "  $0 -d                                 # 后台运行"
    echo "  $0 -d your-token                      # 后台运行，指定 TOKEN"
    echo "  $0 --stop                             # 停止服务"
    echo "  $0 --status                           # 查看状态"
    echo ""
    echo "  HTTP_PORT=8080 $0                     # 使用自定义端口"
    echo "  AUTH_TOKEN=xxx LOG_FILE_PATH=/var/log/notice.log $0 -d"
}

get_pid() {
    if [ -f "$PID_FILE" ]; then
        cat "$PID_FILE"
    fi
}

is_running() {
    local pid=$(get_pid)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        return 0
    fi
    return 1
}

do_start() {
    local daemon=$1
    
    if is_running; then
        echo "Notice Server 已在运行 (PID: $(get_pid))"
        exit 1
    fi
    
    if [ ! -f "./notice-server" ]; then
        echo "错误: 未找到 notice-server 可执行文件"
        exit 1
    fi
    
    echo "启动 Notice Server..."
    echo "HTTP:     http://localhost:${HTTP_PORT:-9090}"
    echo "MQTT TCP: tcp://localhost:${MQTT_TCP_PORT:-9091}"
    echo "MQTT WS:  ws://localhost:${MQTT_WS_PORT:-9092}"
    echo ""
    
    if [ "$daemon" = "true" ]; then
        # 后台运行时禁用控制台日志，使用文件日志
        export LOG_CONSOLE_LEVEL=off
        export LOG_FILE_LEVEL=${LOG_FILE_LEVEL:-debug}
        export LOG_FILE_PATH=${LOG_FILE_PATH:-$DEFAULT_LOG_FILE}
        
        # 确保日志目录存在
        mkdir -p "$(dirname "$LOG_FILE_PATH")"
        
        nohup ./notice-server > /dev/null 2>&1 &
        echo $! > "$PID_FILE"
        echo "已在后台启动 (PID: $!)"
        echo "日志文件: ${LOG_FILE_PATH}"
    else
        exec ./notice-server
    fi
}

do_stop() {
    if ! is_running; then
        echo "Notice Server 未运行"
        rm -f "$PID_FILE"
        return 0
    fi
    
    local pid=$(get_pid)
    echo "停止 Notice Server (PID: $pid)..."
    kill "$pid"
    
    # 等待进程结束
    local count=0
    while kill -0 "$pid" 2>/dev/null && [ $count -lt 30 ]; do
        sleep 1
        count=$((count + 1))
    done
    
    if kill -0 "$pid" 2>/dev/null; then
        echo "强制终止..."
        kill -9 "$pid"
    fi
    
    rm -f "$PID_FILE"
    echo "已停止"
}

do_status() {
    if is_running; then
        echo "Notice Server 正在运行 (PID: $(get_pid))"
    else
        echo "Notice Server 未运行"
        rm -f "$PID_FILE"
    fi
}

# 解析参数
DAEMON=false
ACTION="start"

while [ $# -gt 0 ]; do
    case "$1" in
        -d|--daemon)
            DAEMON=true
            shift
            ;;
        -s|--stop)
            ACTION="stop"
            shift
            ;;
        -r|--restart)
            ACTION="restart"
            shift
            ;;
        --status)
            ACTION="status"
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        -*)
            echo "未知选项: $1"
            show_help
            exit 1
            ;;
        *)
            # 非选项参数作为 TOKEN
            export AUTH_TOKEN="$1"
            shift
            ;;
    esac
done

# 执行操作
case "$ACTION" in
    start)
        do_start "$DAEMON"
        ;;
    stop)
        do_stop
        ;;
    restart)
        do_stop
        sleep 1
        do_start "$DAEMON"
        ;;
    status)
        do_status
        ;;
esac
