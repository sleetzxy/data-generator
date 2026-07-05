@echo off
setlocal EnableDelayedExpansion

set "APP_ROLE=%~1"
if not defined APP_ROLE set "APP_ROLE=web"

call "%~dp0env.bat" %APP_ROLE%
if errorlevel 1 exit /b 1

set "PID_FILE=%APP_HOME%\run\%APP_PID_NAME%"

if not exist "%PID_FILE%" (
    echo %APP_DISPLAY_NAME%: 未找到 PID 文件，服务可能未运行
    exit /b 0
)

set /p PID=<"%PID_FILE%"

tasklist /FI "PID eq %PID%" 2>nul | find /I "%PID%" >nul
if errorlevel 1 (
    echo %APP_DISPLAY_NAME%: 进程 %PID% 不存在，清理 PID 文件
    del /f /q "%PID_FILE%" >nul 2>&1
    exit /b 0
)

echo 正在停止 %APP_DISPLAY_NAME% (PID=%PID%) ...
taskkill /PID %PID% /T >nul 2>&1

for /L %%I in (1,1,30) do (
    tasklist /FI "PID eq %PID%" 2>nul | find /I "%PID%" >nul
    if errorlevel 1 (
        del /f /q "%PID_FILE%" >nul 2>&1
        echo %APP_DISPLAY_NAME%: 已停止
        exit /b 0
    )
    timeout /t 1 /nobreak >nul
)

echo %APP_DISPLAY_NAME%: 优雅停止超时，强制终止 ...
taskkill /PID %PID% /T /F >nul 2>&1
del /f /q "%PID_FILE%" >nul 2>&1
echo %APP_DISPLAY_NAME%: 已强制停止
exit /b 0
