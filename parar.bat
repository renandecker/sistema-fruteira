@echo off
for %%T in ("catalogo-service :8081" "estoque-service :8082" "vendas-service :8083" "retaguarda-service :8084" "pdv-web :5173") do (
  taskkill /FI "WINDOWTITLE eq %%~T*" /T /F >nul 2>nul
)
echo Servicos encerrados.
