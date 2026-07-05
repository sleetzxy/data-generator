@echo off
call "%~dp0stop-service.bat" ai
call "%~dp0stop-service.bat" web
exit /b 0
