@echo off
setlocal
cd /d "%~dp0"
title Fruteira - Launcher

if not exist ".env" ( echo [ERRO] Arquivo .env nao encontrado ^(copie de .env.example^) & pause & exit /b 1 )
for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do set "%%A=%%B"
if "%APP_ENV%"=="" set "APP_ENV=desenvolvimento"
if "%USE_NGINX%"=="" set "USE_NGINX=false"
if /i not "%APP_ENV%"=="desenvolvimento" if /i not "%APP_ENV%"=="producao" ( echo [ERRO] APP_ENV invalido: %APP_ENV% & pause & exit /b 1 )
echo Ambiente: %APP_ENV%  ^(nginx/HTTPS: %USE_NGINX%^)

for %%C in (java mvn node npm) do (
  where %%C >nul 2>nul || ( echo [ERRO] %%C nao encontrado no PATH & pause & exit /b 1 )
)
if /i "%APP_ENV%"=="producao" goto producao

:dev
echo Iniciando microsservicos ^(desenvolvimento^)...
start "catalogo-service :8081" cmd /k "cd /d %~dp0catalogo-service && mvn quarkus:dev -Ddebug=false"
timeout /t 15 /nobreak >nul
start "estoque-service :8082"  cmd /k "cd /d %~dp0estoque-service && mvn quarkus:dev -Ddebug=false"
start "vendas-service :8083"   cmd /k "cd /d %~dp0vendas-service && mvn quarkus:dev -Ddebug=false"
start "retaguarda-service :8084" cmd /k "cd /d %~dp0retaguarda-service && mvn quarkus:dev -Ddebug=false"
start "tef-agent :8090" cmd /k "cd /d %~dp0tef-agent && mvn quarkus:dev -Ddebug=false"
if not exist "%~dp0pdv-web\node_modules" ( pushd pdv-web & call npm install & popd )
start "pdv-web :5173" cmd /k "cd /d %~dp0pdv-web && npm run dev -- --host"
timeout /t 20 /nobreak >nul
start http://localhost:5173
echo.
echo Login local: gerente/gerente, supervisor/supervisor, operador/operador
goto fim

:producao
where docker >nul 2>nul || ( echo [ERRO] docker nao encontrado no PATH & pause & exit /b 1 )
echo %DB_PASS% | findstr /b /i "troque" >nul && ( echo [ERRO] Defina uma DB_PASS segura no .env & pause & exit /b 1 )
echo %KEYCLOAK_ADMIN_PASSWORD% | findstr /b /i "troque" >nul && ( echo [ERRO] Defina KEYCLOAK_ADMIN_PASSWORD segura no .env & pause & exit /b 1 )
echo %KEYCLOAK_DB_PASS% | findstr /b /i "troque" >nul && ( echo [ERRO] Defina KEYCLOAK_DB_PASS segura no .env & pause & exit /b 1 )
set "PERFIL="
if /i not "%USE_NGINX%"=="true" goto prod_build
echo %FRONT_DOMAIN%%KEYCLOAK_DOMAIN% | findstr /i "exemplo" >nul && ( echo [ERRO] Troque FRONT_DOMAIN/KEYCLOAK_DOMAIN pelos dominios reais no .env & pause & exit /b 1 )
if not exist "nginx\certs\live\fruteira\fullchain.pem" ( echo [ERRO] Sem certificado TLS. Rode ssl.bat letsencrypt ^(ou ssl.bat local^) & pause & exit /b 1 )
set "PERFIL=--profile nginx"

:prod_build
if /i not "%USE_NGINX%"=="true" echo Producao local SEM HTTPS ^(USE_NGINX=false^). Para HTTPS real use USE_NGINX=true no .env.
echo Gerando o front ^(dist^)...
pushd pdv-web
if not exist node_modules call npm install
call npm run build || ( popd & echo [ERRO] Build do front falhou & pause & exit /b 1 )
popd
echo Subindo PostgreSQL e Keycloak...
docker compose -f docker-compose.prod.yml %PERFIL% up -d || ( pause & exit /b 1 )
timeout /t 30 /nobreak >nul
echo Compilando e iniciando microsservicos ^(producao^)...
for %%S in (catalogo-service:8081 estoque-service:8082 vendas-service:8083 retaguarda-service:8084 tef-agent:8090) do (
  for /f "tokens=1,2 delims=:" %%N in ("%%S") do (
    start "%%N :%%O" cmd /k "cd /d %~dp0%%N && mvn -q package -DskipTests && java -jar target\quarkus-app\quarkus-run.jar"
  )
)
if /i "%USE_NGINX%"=="true" goto abrir_https
start "pdv-web :5173" cmd /k "cd /d %~dp0pdv-web && npx vite preview --host --port 5173"
timeout /t 60 /nobreak >nul
start %FRONT_URL%
goto msg_prod
:abrir_https
timeout /t 60 /nobreak >nul
start %FRONT_URL%
:msg_prod
echo.
echo Login via Keycloak: %KEYCLOAK_URL% ^(usuarios gerente/supervisor/operador, senha temporaria trocar123^)

:fim
echo Tudo iniciado em janelas separadas. Use parar.bat para encerrar.
endlocal
