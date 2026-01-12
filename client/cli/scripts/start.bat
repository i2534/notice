@echo off
REM Notice CLI Client (Windows)
REM Usage: start.bat BROKER TOKEN [TOPIC] [EXEC_CMD]
REM
REM Examples:
REM   start.bat tcp://localhost:9091 your-token
REM   start.bat wss://mqtt.example.com your-token notice/#
REM   start.bat tcp://localhost:9091 your-token notice/# "handler.bat"
REM
REM Environment Variables:
REM   CLIENT_ID - Client ID (default: cli-client-COMPUTERNAME)
REM   EXEC_CMD  - Command to execute on message received (optional)

setlocal enabledelayedexpansion

REM Switch to script directory
cd /d "%~dp0"

REM Parse arguments
set "BROKER=%~1"
set "TOKEN=%~2"
set "TOPIC=%~3"
set "EXEC_ARG=%~4"

REM Default values
if "%BROKER%"=="" set "BROKER=tcp://localhost:9091"
if "%TOPIC%"=="" set "TOPIC=notice/#"

REM Use argument or environment variable for EXEC
if not "%EXEC_ARG%"=="" (
    set "EXEC_CMD=%EXEC_ARG%"
)

REM Client ID (use environment variable or default)
if "%CLIENT_ID%"=="" set "CLIENT_ID=cli-client-%COMPUTERNAME%"

REM Detect executable
set "CLI="
if exist "notice-cli.exe" (
    set "CLI=notice-cli.exe"
) else if exist "notice-cli" (
    set "CLI=notice-cli"
)

if "%CLI%"=="" (
    echo Error: notice-cli.exe not found
    exit /b 1
)

REM Check TOKEN
if "%TOKEN%"=="" (
    echo Usage: %~nx0 BROKER TOKEN [TOPIC] [EXEC_CMD]
    echo.
    echo Examples:
    echo   %~nx0 tcp://localhost:9091 your-token
    echo   %~nx0 wss://mqtt.example.com your-token notice/#
    echo   %~nx0 tcp://localhost:9091 your-token notice/# "handler.bat"
    echo.
    echo Environment Variables:
    echo   CLIENT_ID - Client ID
    echo   EXEC_CMD  - Command to execute on message received
    exit /b 1
)

echo Starting Notice CLI Client...
echo Broker: %BROKER%
echo Topic:  %TOPIC%
if not "%EXEC_CMD%"=="" echo Exec:   %EXEC_CMD%
echo.

if not "%EXEC_CMD%"=="" (
    "%CLI%" -broker="%BROKER%" -token="%TOKEN%" -topic="%TOPIC%" -id="%CLIENT_ID%" -exec="%EXEC_CMD%"
) else (
    "%CLI%" -broker="%BROKER%" -token="%TOKEN%" -topic="%TOPIC%" -id="%CLIENT_ID%"
)
