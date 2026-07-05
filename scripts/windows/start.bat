@echo off
call "%~dp0start-service.bat" web
exit /b %ERRORLEVEL%
