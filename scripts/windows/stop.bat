@echo off
setlocal EnableDelayedExpansion

call "%~dp0common.bat"

set "PID_FILE=%APP_HOME%\run\data-generator.pid"

if not exist "%PID_FILE%" (
    echo 未找到 PID 文件，服务可能未运行
    exit /b 0
)

set /p PID=<"%PID_FILE%"

tasklist /FI "PID eq %PID%" 2>nul | find /I "%PID%" >nul
if errorlevel 1 (
    echo 进程 %PID% 不存在，清理 PID 文件
    del /f /q "%PID_FILE%" >nul 2>&1
    exit /b 0
)

echo 正在停止 Data Generator (PID=%PID%) ...
taskkill /PID %PID% /T >nul 2>&1

for /L %%I in (1,1,30) do (
    tasklist /FI "PID eq %PID%" 2>nul | find /I "%PID%" >nul
    if errorlevel 1 (
        del /f /q "%PID_FILE%" >nul 2>&1
        echo 已停止
        exit /b 0
    )
    timeout /t 1 /nobreak >nul
)

echo 优雅停止超时，强制终止 ...
taskkill /PID %PID% /T /F >nul 2>&1
del /f /q "%PID_FILE%" >nul 2>&1
echo 已强制停止
exit /b 0
