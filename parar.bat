@echo off
for %%T in ("ngrok :4040" "catalogo-service :8081" "estoque-service :8082" "vendas-service :8083" "retaguarda-service :8084" "tef-agent :8090" "pdv-web :5173") do (
  taskkill /FI "WINDOWTITLE eq %%~T*" /T /F >nul 2>nul
)
set "PORTAS_APP=8081 8082 8083 8084 8090 5173"
if exist .env findstr /b /i /c:"USAR_NGROK=true" .env >nul 2>nul && set "PORTAS_APP=%PORTAS_APP% 4040"
for %%P in (%PORTAS_APP%) do (
  for /f "tokens=5" %%I in ('netstat -ano ^| findstr /R /C:":%%P .*LISTENING"') do taskkill /PID %%I /T /F >nul 2>nul
)
if exist docker-compose.prod.yml ( where docker >nul 2>nul && docker compose -f docker-compose.prod.yml --profile nginx stop >nul 2>nul )
echo Servicos encerrados.
