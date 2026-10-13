@echo off
rem Restaura:  restaurar.bat saida\fruteira-AAAAMMDD-HHMMSS-prod.tar.gz.enc [--senha XXX]
set "FRUTEIRA_CWD=%cd%"
cd /d "%~dp0"
where node >nul 2>nul || (echo Node.js 18+ nao encontrado & exit /b 1)
if "%~1"=="" (echo Uso: restaurar.bat ^<arquivo^> [--senha XXX] [--sim] [--forcar] & exit /b 1)
node restaurar.mjs %*
exit /b %errorlevel%
