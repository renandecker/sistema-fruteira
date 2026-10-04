@echo off
setlocal
cd /d "%~dp0"
title Fruteira - Launcher

for %%C in (java mvn node npm) do (
  where %%C >nul 2>nul || ( echo [ERRO] %%C nao encontrado no PATH & pause & exit /b 1 )
)

echo Iniciando microsservicos...
start "catalogo-service :8081" cmd /k "cd /d %~dp0catalogo-service && mvn quarkus:dev -Ddebug=false"
timeout /t 15 /nobreak >nul
start "estoque-service :8082"  cmd /k "cd /d %~dp0estoque-service && mvn quarkus:dev -Ddebug=false"
start "vendas-service :8083"   cmd /k "cd /d %~dp0vendas-service && mvn quarkus:dev -Ddebug=false"

echo Iniciando PDV...
if not exist "%~dp0pdv-web\node_modules" ( pushd pdv-web & call npm install & popd )
start "retaguarda-service :8084" cmd /k "cd /d %~dp0retaguarda-service && mvn quarkus:dev -Ddebug=false"
start "pdv-web :5173" cmd /k "cd /d %~dp0pdv-web && npm run dev -- --host"

timeout /t 20 /nobreak >nul
start http://localhost:5173
echo.
echo Tudo iniciado em janelas separadas. Use parar.bat para encerrar.
endlocal
