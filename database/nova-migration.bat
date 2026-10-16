@echo off
where node >nul 2>nul || ( echo [ERRO] Node.js 18+ nao encontrado. & exit /b 1 )
node "%~dp0nova-migration.mjs" %*
