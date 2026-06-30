@echo off
call "%~dp0start-service.bat" web
if errorlevel 1 exit /b 1
call "%~dp0start-service.bat" ai
exit /b %ERRORLEVEL%
