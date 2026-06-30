@echo off
setlocal EnableDelayedExpansion

set "APP_ROLE=%~1"
if not defined APP_ROLE set "APP_ROLE=web"

call "%~dp0env.bat" %APP_ROLE%
if errorlevel 1 exit /b 1

set "PID_FILE=%APP_HOME%\run\%APP_PID_NAME%"
set "LOG_DIR=%APP_HOME%\logs"
set "RUN_DIR=%APP_HOME%\run"
set "LOG_FILE=%LOG_DIR%\%APP_LOG_NAME%"

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%RUN_DIR%" mkdir "%RUN_DIR%"
if /I "%APP_ROLE%"=="web" (
    if not exist "%APP_HOME%\data\configs\jobs" mkdir "%APP_HOME%\data\configs\jobs"
    if not exist "%APP_HOME%\data\job-logs" mkdir "%APP_HOME%\data\job-logs"
)

if exist "%PID_FILE%" (
    set /p OLD_PID=<"%PID_FILE%"
    tasklist /FI "PID eq !OLD_PID!" 2>nul | find /I "!OLD_PID!" >nul
    if not errorlevel 1 (
        echo !APP_DISPLAY_NAME! 已在运行 (PID=!OLD_PID!)
        exit /b 1
    )
    del /f /q "%PID_FILE%" >nul 2>&1
)

if /I "!JAVA_SOURCE!"=="bundled" (
    set "JAVA_DESC=内置 JDK (!JAVA_CMD!)"
) else if /I "!JAVA_SOURCE!"=="JAVA_HOME" (
    set "JAVA_DESC=系统 JAVA_HOME (!JAVA_CMD!)"
) else (
    set "JAVA_DESC=系统 PATH (!JAVA_CMD!)"
)

echo 启动 !APP_DISPLAY_NAME! ...
echo   安装目录: %APP_HOME%
echo   Java:     !JAVA_DESC!
echo   端口:     %APP_PORT% (来源: %APP_PORT_SOURCE%)
echo   JAR:      %APP_JAR%
echo   配置:     %APP_CONF_DIR%
echo   日志:     %LOG_FILE%

set "DG_JAVA_CMD=%JAVA_CMD%"
set "DG_APP_JAR=%APP_JAR%"
set "DG_APP_HOME=%APP_HOME%"
set "DG_LOG_FILE=%LOG_FILE%"
set "DG_PID_FILE=%PID_FILE%"
set "DG_JAVA_OPTS=%JAVA_OPTS%"
set "DG_APP_PORT=%APP_PORT%"
set "DG_CONF_DIR=%APP_CONF_DIR:\=/%"
set "DG_SPRING_PROFILE="
set "DG_STARTUP_OK=%APP_STARTUP_OK%"

if exist "%APP_CONF_DIR%\application-local.yml" set "DG_SPRING_PROFILE=local"
if exist "%APP_CONF_DIR%\application-local.yaml" set "DG_SPRING_PROFILE=local"

if exist "%LOG_FILE%" del /f /q "%LOG_FILE%" >nul 2>&1

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$args = @(); if ($env:DG_JAVA_OPTS) { $args += $env:DG_JAVA_OPTS -split '\s+' }; $args += @('-jar', $env:DG_APP_JAR, '--spring.config.additional-location=optional:file:' + $env:DG_CONF_DIR + '/', '--server.port=' + $env:DG_APP_PORT); if ($env:DG_SPRING_PROFILE) { $args += ('--spring.profiles.active=' + $env:DG_SPRING_PROFILE) }; $proc = Start-Process -FilePath $env:DG_JAVA_CMD -ArgumentList $args -WorkingDirectory $env:DG_APP_HOME -WindowStyle Hidden -RedirectStandardOutput $env:DG_LOG_FILE -RedirectStandardError $env:DG_LOG_FILE -PassThru; if ($null -eq $proc) { exit 1 }; $proc.Id | Set-Content -Path $env:DG_PID_FILE -Encoding ASCII -NoNewline"

if errorlevel 1 (
    echo 启动失败
    exit /b 1
)

if not defined STARTUP_TIMEOUT set "STARTUP_TIMEOUT=90"
set "DG_STARTUP_TIMEOUT=%STARTUP_TIMEOUT%"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$pid = [int](Get-Content -LiteralPath $env:DG_PID_FILE -ErrorAction Stop); $log = $env:DG_LOG_FILE; $timeout = [int]$env:DG_STARTUP_TIMEOUT; $fail = @('Application run failed','APPLICATION FAILED TO START','PortInUseException','Address already in use','Web server failed to start','BindException','Failed to bind to'); $okPattern = $env:DG_STARTUP_OK; function Has($patterns) { if (-not (Test-Path -LiteralPath $log)) { return $false }; $t = Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue; foreach ($p in ($patterns -split '\|')) { if ($t -match $p) { return $true } }; return $false }; Write-Host \"等待服务就绪 (最多 ${timeout}s) ...\"; for ($i = 1; $i -le $timeout; $i++) { if (Has $okPattern) { exit 0 }; $proc = Get-Process -Id $pid -ErrorAction SilentlyContinue; if (-not $proc) { Write-Host '错误: 进程已退出，启动失败' -ForegroundColor Red; if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 40 }; exit 1 }; Start-Sleep -Seconds 1 }; if (Has ($fail -join '|')) { Write-Host \"错误: 启动失败，请查看日志: $log\" -ForegroundColor Red; if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 40 }; Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue; exit 1 }; Write-Host \"错误: 启动超时 (${timeout}s)\" -ForegroundColor Red; if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 40 }; Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue; exit 1"

if errorlevel 1 (
    del /f /q "%PID_FILE%" >nul 2>&1
    exit /b 1
)

set /p NEW_PID=<"%PID_FILE%"
echo 已启动 (PID=!NEW_PID!)，访问 http://localhost:%APP_PORT%
exit /b 0
