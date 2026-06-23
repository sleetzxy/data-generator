@echo off
rem 公共环境：解析安装目录、JAR、Java 可执行文件
rem 用法: call env.bat [web|ai]
rem 仅设置脚本内局部变量（setlocal），不修改系统 JAVA_HOME/PATH

set "APP_ROLE=%~1"
if not defined APP_ROLE set "APP_ROLE=web"

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "APP_HOME=%%~fI"

call :configure_role
if errorlevel 1 exit /b 1

set "APP_JAR="
for %%F in ("%APP_HOME%\lib\%APP_JAR_PREFIX%*.jar") do (
    if not defined APP_JAR set "APP_JAR=%%~fF"
)

if not defined APP_JAR (
    echo 错误: lib 目录下未找到 %APP_JAR_PREFIX%*.jar: %APP_HOME%\lib >&2
    exit /b 1
)

set "JAVA_CMD="
set "JAVA_SOURCE="

if exist "%APP_HOME%\jdk\bin\java.exe" (
    set "JAVA_CMD=%APP_HOME%\jdk\bin\java.exe"
    set "JAVA_SOURCE=bundled"
    goto :java_ready
)

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
        set "JAVA_SOURCE=JAVA_HOME"
        goto :java_ready
    )
)

for /f "delims=" %%J in ('where java 2^>nul') do (
    set "JAVA_CMD=%%~fJ"
    set "JAVA_SOURCE=PATH"
    goto :java_ready
)

echo 错误: 未找到 Java。>&2
echo   方式一: 将 JDK 21+ 解压到 %APP_HOME%\jdk（仅本应用使用，不改系统 JDK）>&2
echo   方式二: 安装系统 JDK 并设置 JAVA_HOME 或加入 PATH>&2
exit /b 1

:java_ready
set "JAVA_OPTS="
if exist "%APP_HOME%\conf\java.opts" (
    for /f "usebackq tokens=1,* delims==" %%A in ("%APP_HOME%\conf\java.opts") do (
        if /I "%%~A"=="JAVA_OPTS" set "JAVA_OPTS=%%~B"
    )
)

call :resolve_server_port
exit /b 0

:configure_role
if /I "%APP_ROLE%"=="web" goto :role_web
if /I "%APP_ROLE%"=="ai" goto :role_ai
echo 错误: 未知服务角色 %APP_ROLE%（可选 web / ai）>&2
exit /b 1

:role_web
set "APP_JAR_PREFIX=dg-web"
set "APP_PID_NAME=data-generator.pid"
set "APP_LOG_NAME=console.log"
set "APP_CONF_DIR=%APP_HOME%\conf"
set "APP_DEFAULT_PORT=8080"
set "APP_STARTUP_OK=Started DataGeneratorApplication|Tomcat started on port"
set "APP_DISPLAY_NAME=Data Generator (Web)"
exit /b 0

:role_ai
set "APP_JAR_PREFIX=dg-ai"
set "APP_PID_NAME=dg-ai.pid"
set "APP_LOG_NAME=ai-console.log"
set "APP_CONF_DIR=%APP_HOME%\conf\dg-ai"
set "APP_DEFAULT_PORT=8081"
set "APP_STARTUP_OK=Started AiApplication|Tomcat started on port"
set "APP_DISPLAY_NAME=Data Generator (AI)"
exit /b 0

:resolve_server_port
if /I "%APP_ROLE%"=="web" if defined SERVER_PORT (
    set "APP_PORT=%SERVER_PORT%"
    set "APP_PORT_SOURCE=环境变量 SERVER_PORT"
    exit /b 0
)

if /I "%APP_ROLE%"=="ai" if defined AI_SERVER_PORT (
    set "APP_PORT=%AI_SERVER_PORT%"
    set "APP_PORT_SOURCE=环境变量 AI_SERVER_PORT"
    exit /b 0
)

set "APP_PORT="
set "APP_PORT_SOURCE="

for %%F in (
    "%APP_CONF_DIR%\application-local.yml"
    "%APP_CONF_DIR%\application-local.yaml"
    "%APP_CONF_DIR%\application.yml"
    "%APP_CONF_DIR%\application.yaml"
) do (
    if exist %%F if not defined APP_PORT (
        for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command ^
            "$path='%%~fF'; $port=$null; Get-Content -LiteralPath $path -ErrorAction SilentlyContinue | ForEach-Object { $_ = $_.TrimEnd([char]13); if ($_ -match '^\s*server\.port:\s*(\d+)') { $port=$matches[1]; break } if ($_ -match '^\s*server:\s*$') { $in=$true; return } if ($in -and $_ -match '^\s+port:\s*(\d+)') { $port=$matches[1]; break } if ($_ -match '^\S') { $in=$false } }; if ($port) { Write-Output $port }"`) do (
            set "APP_PORT=%%P"
            set "APP_PORT_SOURCE=%%~fF"
        )
    )
)

if not defined APP_PORT (
    for %%F in ("%APP_CONF_DIR%\*.yml" "%APP_CONF_DIR%\*.yaml") do (
        if exist %%F if not defined APP_PORT (
            for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command ^
                "$path='%%~fF'; $port=$null; Get-Content -LiteralPath $path -ErrorAction SilentlyContinue | ForEach-Object { $_ = $_.TrimEnd([char]13); if ($_ -match '^\s*server\.port:\s*(\d+)') { $port=$matches[1]; break } if ($_ -match '^\s*server:\s*$') { $in=$true; return } if ($in -and $_ -match '^\s+port:\s*(\d+)') { $port=$matches[1]; break } if ($_ -match '^\S') { $in=$false } }; if ($port) { Write-Output $port }"`) do (
                set "APP_PORT=%%P"
                set "APP_PORT_SOURCE=%%~fF"
            )
        )
    )
)

if not defined APP_PORT (
    set "APP_PORT=%APP_DEFAULT_PORT%"
    set "APP_PORT_SOURCE=默认值"
)
exit /b 0
