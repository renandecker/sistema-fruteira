@echo off
cd /d "%~dp0"
for %%T in (catalogo estoque vendas retaguarda tef-agent web) do taskkill /FI "WINDOWTITLE eq %%T*" /T /F >nul 2>nul
where docker >nul 2>nul && docker compose -f docker-compose.prod.yml --profile nginx stop >nul 2>nul
echo Servicos encerrados.
