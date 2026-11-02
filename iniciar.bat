@echo off
setlocal
cd /d "%~dp0"
title Fruteira - Launcher

if not exist ".env" ( echo [ERRO] Arquivo .env nao encontrado ^(copie de .env.example^) & pause & exit /b 1 )
for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do set "%%A=%%B"
set "FRUTEIRA_HOME=%~dp0"
if not defined FISCAL_CERT_PATH if exist "%~dp0certificado.pfx" set "FISCAL_CERT_PATH=%~dp0certificado.pfx"
if "%APP_ENV%"=="" set "APP_ENV=desenvolvimento"
if /i not "%APP_ENV%"=="desenvolvimento" if /i not "%APP_ENV%"=="producao" ( echo [ERRO] APP_ENV invalido: %APP_ENV% & pause & exit /b 1 )
echo Ambiente: %APP_ENV%

for %%C in (java mvn node npm) do (
  where %%C >nul 2>nul || ( echo [ERRO] %%C nao encontrado no PATH & pause & exit /b 1 )
)
if /i "%LIBERAR_PORTAS%"=="false" goto sem_liberar
set "PORTAS_APP=8081 8082 8083 8084 8090 5173"
if /i "%USAR_NGROK%"=="true" set "PORTAS_APP=%PORTAS_APP% 4040"
echo Liberando as portas do sistema ^(%PORTAS_APP%^)...
for %%P in (%PORTAS_APP%) do (
  for /f "tokens=5" %%I in ('netstat -ano ^| findstr /R /C:":%%P .*LISTENING"') do (
    echo   Porta %%P em uso pelo PID %%I: encerrando...
    taskkill /PID %%I /T /F >nul 2>nul
  )
)
:sem_liberar
rem Sobras de execucoes anteriores (janelas que falharam mas o java continua vivo, em "recuperacao" do modo dev) seguram o arquivo do H2 aberto;
rem apagar o .lock.db com elas vivas faz os novos processos abrirem o banco direto e falharem com "IO Exception 90028 / Acquisition timeout". Encerra so o java do Quarkus/deste projeto.
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match 'quarkus|fruteira|h2' -and $_.ProcessId -ne $PID } | ForEach-Object { Write-Host ('Encerrando java antigo PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force }" 2>nul
rem Desenvolvimento: banco PostgreSQL em DOCKER (padrao) ou H2 em arquivo (USAR_DOCKER=false no .env)
if /i not "%APP_ENV%"=="desenvolvimento" goto sem_lock
if "%USAR_DOCKER%"=="" set "USAR_DOCKER=true"
if /i "%USAR_DOCKER%"=="false" goto h2_local
where docker >nul 2>nul || ( echo [ERRO] docker nao encontrado. Instale o Docker Desktop ou use USAR_DOCKER=false no .env ^(H2 em arquivo^). & pause & exit /b 1 )
if "%DB_USER%"=="" set "DB_USER=fruteira"
if "%DB_PASS%"=="" set "DB_PASS=fruteira"
set "DEV_DB_KIND=postgresql"
if "%PG_DOCKER_PORT%"=="" set "PG_DOCKER_PORT=5442"
set "DEV_DB_URL=jdbc:postgresql://localhost:%PG_DOCKER_PORT%/fruteira"
echo Docker ^(local^): PostgreSQL...
docker compose up -d postgres || ( echo [ERRO] docker compose falhou ^(o Docker Desktop esta aberto?^). Sem Docker: USAR_DOCKER=false no .env & pause & exit /b 1 )
powershell -NoProfile -Command "for($i=0;$i -lt 40;$i++){ if((docker compose ps --format '{{.Health}}' postgres) -match 'healthy'){ exit 0 }; Start-Sleep 2 }; exit 1" || ( echo [ERRO] PostgreSQL do Docker nao ficou saudavel: docker compose logs postgres & pause & exit /b 1 )
echo PostgreSQL no ar ^(Docker, porta %PG_DOCKER_PORT%^).
goto sem_lock
:h2_local
if not exist "%~dp0data" mkdir "%~dp0data"
del /q "%~dp0data\*.lock.db" >nul 2>nul
set "DEV_DB_KIND=h2"
if "%H2_ARQUIVO%"=="" set "H2_ARQUIVO=../data/fruteira"
set "DEV_DB_URL=jdbc:h2:file:%H2_ARQUIVO%;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1"
echo %~dp0| findstr /i "onedrive" >nul && echo [AVISO] Projeto no OneDrive: o H2 em arquivo pode travar. Prefira o Docker ^(padrao^) ou defina H2_ARQUIVO fora do OneDrive.
:sem_lock
rem Ngrok (opcional, USAR_NGROK=true): tunel HTTPS publico so para o webhook do Pix (porta 8083); o vendas-service descobre o endereco pela API local (4040)
if /i not "%USAR_NGROK%"=="true" goto sem_ngrok
where ngrok >nul 2>nul || ( echo [ERRO] USAR_NGROK=true, mas o ngrok nao esta no PATH ^(https://ngrok.com/download; depois: ngrok config add-authtoken SEU_TOKEN^) & pause & exit /b 1 )
set "NGROK_API=http://127.0.0.1:4040/api/tunnels"
set "NGROK_ARGS_BAT=http 8083"
if not "%NGROK_DOMINIO%"=="" set "NGROK_ARGS_BAT=http 8083 --url=%NGROK_DOMINIO%"
echo Iniciando o tunel Ngrok ^(porta 8083^)...
start "ngrok :4040" cmd /k "ngrok %NGROK_ARGS_BAT% %NGROK_ARGS%"
timeout /t 8 /nobreak >nul
echo Endereco do tunel: http://127.0.0.1:4040 ^(painel do Ngrok^). Pix real: Configuracoes - PIX - Registrar webhook.
:sem_ngrok
if /i "%APP_ENV%"=="producao" goto producao

:dev
if not exist "%~dp0data" mkdir "%~dp0data"
echo Iniciando microsservicos ^(desenvolvimento^)...
rem retaguarda PRIMEIRO: e o dono do Flyway (cria/atualiza o banco); os outros so validam o schema
start "retaguarda-service :8084" cmd /k "cd /d %~dp0retaguarda-service && mvn quarkus:dev -Ddebug=false"
echo Aguardando o retaguarda-service criar/atualizar o banco ^(Flyway^)...
powershell -NoProfile -Command "for($i=0;$i -lt 90;$i++){ $c=0; try{ $c=[int](Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 http://127.0.0.1:8084/).StatusCode }catch{ if($_.Exception.Response){ $c=[int]$_.Exception.Response.StatusCode } }; if($c -gt 0 -and $c -lt 500){ exit 0 }; Start-Sleep 2 }; exit 1" || ( echo [ERRO] retaguarda-service nao subiu em 3 min: ele e o dono do banco ^(Flyway^) e sem ele os outros falham com "missing table". Veja a janela dele. & pause & exit /b 1 )
start "catalogo-service :8081" cmd /k "cd /d %~dp0catalogo-service && mvn quarkus:dev -Ddebug=false"
start "estoque-service :8082"  cmd /k "cd /d %~dp0estoque-service && mvn quarkus:dev -Ddebug=false"
start "vendas-service :8083"   cmd /k "cd /d %~dp0vendas-service && mvn quarkus:dev -Ddebug=false"
start "tef-agent :8090" cmd /k "cd /d %~dp0tef-agent && mvn quarkus:dev -Ddebug=false"
if not exist "%~dp0pdv-web\node_modules" ( pushd pdv-web & call npm install & popd )
start "pdv-web :5173" cmd /k "cd /d %~dp0pdv-web && npm run dev -- --host"
timeout /t 20 /nobreak >nul
start http://localhost:5173
echo.
echo Login local: gerente/gerente, supervisor/supervisor, operador/operador
goto fim

:producao
rem Teste LOCAL do perfil de producao: PostgreSQL em Docker, login proprio com token. A producao real nao usa Docker (gerar-producao.bat + producao\iniciar.bat).
where docker >nul 2>nul || (echo [ERRO] docker nao encontrado & pause & exit /b 1)
if "%JWT_SEGREDO%"=="" ( echo [ERRO] Defina JWT_SEGREDO ^(minimo 32 caracteres^) e ADMIN_SENHA_INICIAL no .env & pause & exit /b 1 )
if "%ADMIN_SENHA_INICIAL%"=="" ( echo [ERRO] Defina ADMIN_SENHA_INICIAL no .env & pause & exit /b 1 )
if not exist run mkdir run
powershell -NoProfile -Command "$k=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($env:JWT_SEGREDO)).TrimEnd('=').Replace('+','-').Replace('/','_'); [IO.File]::WriteAllText('run\jwt.jwk','{\"kty\":\"oct\",\"alg\":\"HS256\",\"use\":\"sig\",\"k\":\"'+$k+'\"}')"
set "JWT_VERIFY_JWK=%~dp0run\jwt.jwk"
if "%PG_DOCKER_PORT%"=="" set "PG_DOCKER_PORT=5442"
set "DB_URL=jdbc:postgresql://localhost:%PG_DOCKER_PORT%/fruteira"
docker compose up -d || (echo [ERRO] docker compose falhou ^(o Docker esta rodando?^) & pause & exit /b 1)
echo Aguardando PostgreSQL...
timeout /t 15 /nobreak >nul
for %%S in (retaguarda-service:8084 catalogo-service:8081 estoque-service:8082 vendas-service:8083 tef-agent:8090) do for /f "tokens=1,2 delims=:" %%a in ("%%S") do (
  pushd %%a & call mvn -q package -DskipTests & popd
  start "%%a :%%b" cmd /k "cd /d %~dp0%%a && java -jar target\quarkus-app\quarkus-run.jar"
  if "%%a"=="retaguarda-service" timeout /t 40 /nobreak >nul
)
if not exist "%~dp0pdv-web\node_modules" ( pushd pdv-web & call npm install & popd )
pushd pdv-web & set APP_ENV=producao& call npm run build & popd
start "pdv-web :5173" cmd /k "cd /d %~dp0pdv-web && npx vite preview --host --port 5173"
timeout /t 10 /nobreak >nul
start http://localhost:5173
goto fim

:fim
echo Tudo iniciado em janelas separadas. Use parar.bat para encerrar.
endlocal
