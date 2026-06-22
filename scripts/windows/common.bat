@echo off
rem 仅解析安装目录，不检测 Java

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "APP_HOME=%%~fI"
exit /b 0
