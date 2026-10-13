@echo off
setlocal
cd /d "%~dp0"
title Fruteira - Producao

rem ---- 1) arquivos necessarios: se faltar nesta pasta, procura na raiz do projeto (pasta acima) ----
set "RAIZ=%~dp0.."
set "FALTOU=0"
call :copiar docker-compose.prod.yml
if not exist "docker-compose.prod.yml" ( echo [ERRO] docker-compose.prod.yml nao encontrado aqui nem em %RAIZ% & set "FALTOU=1" )
call :copiar keycloak
if not exist "keycloak" ( echo [ERRO] pasta keycloak nao encontrada aqui nem em %RAIZ% & set "FALTOU=1" )
call :copiar nginx\templates
call :copiar nginx\snippets
call :copiar database
call :copiar servidor-web.mjs producao-modelo\servidor-web.mjs
call :copiar preparar-env.ps1 producao-modelo\preparar-env.ps1
if not exist "web" call :copiar web pdv-web\dist
if not exist "servicos" echo [AVISO] servicos\ ^(jars^) ausente: rode gerar-producao.bat na raiz do projeto
if not exist "nginx\certs" mkdir "nginx\certs"
if not exist logs mkdir logs
if not exist backups mkdir backups
if exist "docker-compose.prod.yml" powershell -NoProfile -Command "(Get-Content docker-compose.prod.yml) -replace './pdv-web/dist','./web' | Set-Content -Encoding UTF8 docker-compose.prod.yml"
set "NOVOENV=0"
if not exist ".env" (
  if exist "%RAIZ%\.env" ( copy /y "%RAIZ%\.env" ".env" >nul & echo .env criado a partir de %RAIZ%\.env & set "NOVOENV=1" ) else if exist "%RAIZ%\.env.example" ( copy /y "%RAIZ%\.env.example" ".env" >nul & echo .env criado a partir de %RAIZ%\.env.example & set "NOVOENV=1" ) else ( echo [ERRO] .env nao encontrado aqui nem em %RAIZ% & pause & exit /b 1 )
)
if "%FALTOU%"=="1" ( pause & exit /b 1 )

rem ---- 2) senhas: vazio ou de exemplo ^("troque-..."^) vira senha aleatoria gravada no .env ----
if not exist "preparar-env.ps1" ( echo [ERRO] preparar-env.ps1 nao encontrado & pause & exit /b 1 )
if "%NOVOENV%"=="1" ( powershell -NoProfile -ExecutionPolicy Bypass -File preparar-env.ps1 -ForcarProducao ) else ( powershell -NoProfile -ExecutionPolicy Bypass -File preparar-env.ps1 )
if errorlevel 1 ( echo [ERRO] Nao foi possivel preparar o .env & pause & exit /b 1 )
if /i "%~1"=="--preparar" ( echo Preparacao concluida ^(--preparar^): nada foi iniciado. & exit /b 0 )
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
exit /b 0

:copiar
rem %1 = caminho relativo; %2 = outro lugar onde procurar na raiz (opcional)
if exist "%~1" exit /b 0
set "ORIGEM="
if exist "%RAIZ%\%~1" set "ORIGEM=%RAIZ%\%~1"
if not defined ORIGEM if not "%~2"=="" if exist "%RAIZ%\%~2" set "ORIGEM=%RAIZ%\%~2"
if not defined ORIGEM exit /b 1
for %%P in ("%~1") do if not exist "%%~dpP" mkdir "%%~dpP" >nul 2>nul
if exist "%ORIGEM%\*" ( xcopy /e /i /q /y "%ORIGEM%" "%~1" >nul ) else ( copy /y "%ORIGEM%" "%~1" >nul )
echo %~1 nao estava aqui: copiado de %ORIGEM%
exit /b 0
