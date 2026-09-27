@echo off
setlocal EnableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0"
if not exist out mkdir out
(for /r src %%f in (*.java) do (
  set "fonte=%%f"
  echo "!fonte:\=/!"
)) > out\fontes.txt
javac -encoding UTF-8 --release 11 -d out @out\fontes.txt
if errorlevel 1 exit /b 1
echo Compilacao concluida.
