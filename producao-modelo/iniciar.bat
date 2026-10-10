@echo off
setlocal
cd /d "%~dp0"
title Fruteira - Producao
if not exist ".env" ( echo [ERRO] .env nao encontrado & pause & exit /b 1 )
for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do set "%%A=%%B"
if "%USE_NGINX%"=="" set "USE_NGINX=false"
for %%C in (java docker) do ( where %%C >nul 2>nul || ( echo [ERRO] %%C nao encontrado no PATH & pause & exit /b 1 ) )
if /i not "%USE_NGINX%"=="true" ( where node >nul 2>nul || ( echo [ERRO] node nao encontrado no PATH & pause & exit /b 1 ) )
echo %DB_PASS% | findstr /b /i "troque" >nul && ( echo [ERRO] Defina DB_PASS segura no .env & pause & exit /b 1 )
echo %KEYCLOAK_ADMIN_PASSWORD% | findstr /b /i "troque" >nul && ( echo [ERRO] Defina KEYCLOAK_ADMIN_PASSWORD segura no .env & pause & exit /b 1 )
echo %KEYCLOAK_DB_PASS% | findstr /b /i "troque" >nul && ( echo [ERRO] Defina KEYCLOAK_DB_PASS segura no .env & pause & exit /b 1 )
set "PERFIL="
if /i "%USE_NGINX%"=="true" (
  echo %FRONT_DOMAIN%%KEYCLOAK_DOMAIN% | findstr /i "exemplo" >nul && ( echo [ERRO] Troque os dominios de exemplo no .env & pause & exit /b 1 )
  if not exist "nginx\certs\live\fruteira\fullchain.pem" ( echo [ERRO] Sem certificado TLS. Rode ssl.bat letsencrypt ^(ou ssl.bat local^) & pause & exit /b 1 )
  set "PERFIL=--profile nginx"
)
if not exist logs mkdir logs
echo Subindo PostgreSQL e Keycloak...
docker compose -f docker-compose.prod.yml %PERFIL% up -d || ( pause & exit /b 1 )
timeout /t 30 /nobreak >nul
for %%S in (catalogo estoque vendas retaguarda tef-agent) do (
  start "%%S" /min cmd /c "cd /d %~dp0servicos\%%S && java -Xmx384m -jar quarkus-run.jar > %~dp0logs\%%S.log 2>&1"
)
if /i not "%USE_NGINX%"=="true" start "web" /min cmd /c "node servidor-web.mjs 5173 > logs\web.log 2>&1"
timeout /t 60 /nobreak >nul
if "%FRONT_URL%"=="" ( start http://localhost:5173 ) else ( start %FRONT_URL% )
echo Login via Keycloak (gerente/supervisor/operador, senha temporaria trocar123). Use parar.bat para encerrar.
endlocal
