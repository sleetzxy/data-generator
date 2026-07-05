@echo off
call "%~dp0stop-service.bat" web
exit /b %ERRORLEVEL%
