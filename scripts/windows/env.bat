@echo off
rem 公共环境：解析安装目录、JAR、Java 可执行文件
rem 仅设置脚本内局部变量（setlocal），不修改系统 JAVA_HOME/PATH

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "APP_HOME=%%~fI"

set "APP_JAR="
for %%F in ("%APP_HOME%\lib\*.jar") do (
    if not defined APP_JAR set "APP_JAR=%%~fF"
)

if not defined APP_JAR (
    echo 错误: lib 目录下没有 jar 包: %APP_HOME%\lib >&2
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

:resolve_server_port
if defined SERVER_PORT (
    set "APP_PORT=%SERVER_PORT%"
    set "APP_PORT_SOURCE=环境变量 SERVER_PORT"
    exit /b 0
)

set "APP_PORT="
set "APP_PORT_SOURCE="

for %%F in (
    "%APP_HOME%\conf\application-local.yml"
    "%APP_HOME%\conf\application-local.yaml"
    "%APP_HOME%\conf\application.yml"
    "%APP_HOME%\conf\application.yaml"
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
    for %%F in ("%APP_HOME%\conf\*.yml" "%APP_HOME%\conf\*.yaml") do (
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
    set "APP_PORT=8080"
    set "APP_PORT_SOURCE=默认值"
)
exit /b 0
