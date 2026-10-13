@echo off
rem Backup da Fruteira:  backup.bat [--destino E:\fruteira] [--senha XXX]   (config em backup.env)
set "FRUTEIRA_CWD=%cd%"
cd /d "%~dp0"
where node >nul 2>nul || (echo Node.js 18+ nao encontrado: https://nodejs.org & exit /b 1)
node backup.mjs %*
exit /b %errorlevel%
