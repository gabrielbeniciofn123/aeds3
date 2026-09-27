@echo off
chcp 65001 >nul
cd /d "%~dp0"
call compilar.bat
if errorlevel 1 exit /b 1
if "%~1"=="--base-100k" (
  java -Dfile.encoding=UTF-8 -Xmx1g -cp out TesteTP2 --base-100k
  exit /b
)
for %%t in (TesteArvoreBMais TesteHashEstendido TesteListaInvertida TesteTP2 TesteRecuperacaoTP2 TesteRevisaoTP2 TesteTP1) do (
  java -Dfile.encoding=UTF-8 -Xmx1g -cp out %%t
  if errorlevel 1 exit /b 1
)
