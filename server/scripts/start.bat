@echo off
chcp 65001 >nul 2>&1
REM Notice Server 启动脚本 (Windows)
REM 使用方法: start.bat [AUTH_TOKEN]
REM          start.bat --help

cd /d "%~dp0"

REM 显示帮助
if "%~1"=="--help" goto :show_help
if "%~1"=="-h" goto :show_help

REM 从命令行参数获取 TOKEN
if not "%~1"=="" set AUTH_TOKEN=%~1

if not exist "notice-server.exe" (
    echo 错误: 未找到 notice-server.exe 可执行文件
    pause
    exit /b 1
)

REM 设置默认值
if not defined HTTP_PORT set HTTP_PORT=9090
if not defined MQTT_TCP_PORT set MQTT_TCP_PORT=9091
if not defined MQTT_WS_PORT set MQTT_WS_PORT=9092
if not defined LOG_FILE_PATH set LOG_FILE_PATH=logs\server.log

echo 启动 Notice Server...
echo HTTP:     http://localhost:%HTTP_PORT%
echo MQTT TCP: tcp://localhost:%MQTT_TCP_PORT%
echo MQTT WS:  ws://localhost:%MQTT_WS_PORT%
echo.

notice-server.exe
goto :eof

:show_help
echo Notice Server 启动脚本 (Windows)
echo.
echo 使用方法: %~nx0 [AUTH_TOKEN]
echo.
echo 环境变量配置:
echo.
echo   基础配置:
echo     HTTP_PORT             HTTP 服务端口 (默认: 9090)
echo     MQTT_TCP_PORT         MQTT TCP 端口 (默认: 9091)
echo     MQTT_WS_PORT          MQTT WebSocket 端口 (默认: 9092)
echo     MQTT_TOPIC            默认消息主题 (默认: notice)
echo.
echo   认证配置:
echo     AUTH_TOKEN            访问令牌 (不设置则自动生成)
echo.
echo   限流配置:
echo     RATE_LIMIT_MAX_FAILURES   最大失败次数 (默认: 5)
echo     RATE_LIMIT_BLOCK_TIME     封禁时间/秒 (默认: 900)
echo     RATE_LIMIT_WINDOW_TIME    统计窗口/秒 (默认: 300)
echo.
echo   日志配置:
echo     LOG_CONSOLE_LEVEL     控制台日志级别: debug/info/warn/error/off (默认: info)
echo     LOG_FILE_LEVEL        文件日志级别: debug/info/warn/error/off (默认: debug)
echo     LOG_FILE_PATH         日志文件路径 (默认: logs\server.log)
echo     LOG_PRETTY            控制台美化输出: true/false (默认: true)
echo     LOG_ROTATE_DAYS       日志轮转天数 (默认: 1)
echo     LOG_MAX_FILES         保留日志文件数 (默认: 7)
echo.
echo   MQTT 会话配置:
echo     MQTT_SESSION_EXPIRY   会话过期时间/秒 (默认: 3600)
echo     MQTT_MESSAGE_EXPIRY   消息过期时间/秒 (默认: 86400)
echo.
echo 示例:
echo   %~nx0                           启动服务
echo   %~nx0 your-token                指定 TOKEN 启动
echo   set HTTP_PORT=8080 ^&^& %~nx0   自定义端口启动
echo.
goto :eof