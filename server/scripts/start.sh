#!/bin/bash
# Notice Server 启动脚本
# 配置说明请参考 config.yaml

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PID_FILE="./notice-server.pid"
CONFIG_FILE="${CONFIG_FILE:-./config.yaml}"

show_help() {
    echo "Notice Server 启动脚本"
    echo ""
    echo "使用方法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  -c FILE     指定配置文件 (默认: config.yaml)"
    echo "  -d          后台运行"
    echo "  -s          停止服务"
    echo "  -r          重启服务"
    echo "  --status    查看运行状态"
    echo "  -h          显示此帮助"
    echo ""
    echo "示例:"
    echo "  $0                    # 前台运行"
    echo "  $0 -d                 # 后台运行"
    echo "  $0 -c prod.yaml -d    # 使用指定配置后台运行"
    echo "  $0 -s                 # 停止服务"
    echo "  $0 -r                 # 重启服务"
    echo ""
    echo "配置说明请参考 config.yaml 文件"
}

get_pid() {
    [ -f "$PID_FILE" ] && cat "$PID_FILE"
}

is_running() {
    local pid=$(get_pid)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
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

    [ ! -x "./notice-server" ] && chmod +x ./notice-server

    echo "启动 Notice Server..."
    [ -f "$CONFIG_FILE" ] && echo "配置文件: $CONFIG_FILE"
    echo ""

    if [ "$daemon" = "true" ]; then
        # 后台运行：禁用控制台日志
        LOG_CONSOLE_LEVEL=off nohup ./notice-server -c "$CONFIG_FILE" > /dev/null 2>&1 &
        echo $! > "$PID_FILE"
        echo "已在后台启动 (PID: $!)"
    else
        exec ./notice-server -c "$CONFIG_FILE"
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
        -c)
            CONFIG_FILE="$2"
            shift 2
            ;;
        -d) DAEMON=true; shift ;;
        -s) ACTION="stop"; shift ;;
        -r) ACTION="restart"; shift ;;
        --status) ACTION="status"; shift ;;
        -h|--help) show_help; exit 0 ;;
        *) echo "未知选项: $1"; show_help; exit 1 ;;
    esac
done

# 执行操作
case "$ACTION" in
    start) do_start "$DAEMON" ;;
    stop) do_stop ;;
    restart) do_stop; sleep 1; do_start "$DAEMON" ;;
    status) do_status ;;
esac
