@echo off
setlocal

set "APP_ROLE=%~1"
if not defined APP_ROLE set "APP_ROLE=web"

call "%~dp0env.bat" %APP_ROLE%
if errorlevel 1 exit /b 1

set "PID_FILE=%APP_HOME%\run\%APP_PID_NAME%"

if not exist "%PID_FILE%" (
    echo %APP_DISPLAY_NAME%: 未运行
    exit /b 1
)

set /p PID=<"%PID_FILE%"
tasklist /FI "PID eq %PID%" 2>nul | find /I "%PID%" >nul
if not errorlevel 1 (
    echo %APP_DISPLAY_NAME%: 运行中 (PID=%PID%, 端口=%APP_PORT%)
    exit /b 0
)

echo %APP_DISPLAY_NAME%: 未运行 (残留 PID 文件: %PID%)
exit /b 1
