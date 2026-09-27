@echo off
chcp 65001 >nul
cd /d "%~dp0"
call compilar.bat
if errorlevel 1 exit /b 1
java -Dfile.encoding=UTF-8 -Xmx1g -cp out DemonstracaoTP2
