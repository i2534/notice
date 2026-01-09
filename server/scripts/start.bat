@echo off
chcp 65001 >nul 2>&1
REM Notice Server 启动脚本 (Windows)
REM 配置说明请参考 config.yaml

cd /d "%~dp0"

if "%~1"=="-h" goto :show_help
if "%~1"=="--help" goto :show_help

if not exist "notice-server.exe" (
    echo 错误: 未找到 notice-server.exe 可执行文件
    pause
    exit /b 1
)

set CONFIG_FILE=config.yaml
if not "%~1"=="" set CONFIG_FILE=%~1

echo 启动 Notice Server...
if exist "%CONFIG_FILE%" echo 配置文件: %CONFIG_FILE%
echo.

notice-server.exe -c %CONFIG_FILE%
goto :eof

:show_help
echo Notice Server 启动脚本 (Windows)
echo.
echo 使用方法: %~nx0 [配置文件]
echo.
echo 示例:
echo   %~nx0                  使用默认 config.yaml 启动
echo   %~nx0 prod.yaml        使用指定配置文件启动
echo.
echo 配置说明请参考 config.yaml 文件
goto :eof
