@echo off
setlocal
cd /d "%~dp0.."
if exist ".env" for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do set "%%A=%%B"
if "%DB_USER%"=="" set "DB_USER=postgres"
where psql >nul 2>nul && ( set "PGPASSWORD=%DB_PASS%" & psql -h localhost -U %DB_USER% -d fruteira -v ON_ERROR_STOP=1 -f database\pre_cadastro_frutas_verduras.sql & goto fim )
where docker >nul 2>nul && ( docker compose -f docker-compose.prod.yml exec -T postgres psql -U %DB_USER% -d fruteira -v ON_ERROR_STOP=1 < database\pre_cadastro_frutas_verduras.sql & goto fim )
echo [ERRO] Nem psql nem o container postgres estao disponiveis.
echo Em desenvolvimento o pre-cadastro ja roda sozinho (H2) e pelo botao "Importar frutas e verduras" na tela Produtos.
exit /b 1
:fim
echo Pre-cadastro aplicado.
