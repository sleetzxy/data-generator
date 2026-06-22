@echo off
setlocal

call "%~dp0common.bat"

set "PID_FILE=%APP_HOME%\run\data-generator.pid"

if not exist "%PID_FILE%" (
    echo 状态: 未运行
    exit /b 1
)

set /p PID=<"%PID_FILE%"
tasklist /FI "PID eq %PID%" 2>nul | find /I "%PID%" >nul
if not errorlevel 1 (
    echo 状态: 运行中 (PID=%PID%)
    exit /b 0
)

echo 状态: 未运行 (残留 PID 文件: %PID%)
exit /b 1
