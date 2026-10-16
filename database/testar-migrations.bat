@echo off
where node >nul 2>nul || ( echo [ERRO] Node.js 18+ nao encontrado. & exit /b 1 )
cd /d "%~dp0teste"
if not exist "node_modules\@electric-sql\pglite" call npm install --no-audit --no-fund --silent
node testar-migrations.mjs
