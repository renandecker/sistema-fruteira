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
rem Desenvolvimento: sobra de execucao anterior deixa data\fruteira.lock.db e trava o banco H2; as portas acabaram de ser liberadas, entao pode apagar
if /i not "%APP_ENV%"=="desenvolvimento" goto sem_lock
if not exist "%~dp0data" mkdir "%~dp0data"
del /q "%~dp0data\*.lock.db" >nul 2>nul
echo %~dp0| findstr /i "onedrive" >nul && echo [AVISO] O projeto esta dentro do OneDrive: a sincronizacao pode travar o banco ^(data\fruteira.mv.db^). Se der erro de banco, mova a pasta para fora do OneDrive ^(ex.: C:\fruteira^).
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
powershell -NoProfile -Command "for($i=0;$i -lt 90;$i++){ try{ (New-Object Net.Sockets.TcpClient('127.0.0.1',8084)).Close(); exit 0 }catch{ Start-Sleep 2 } }; exit 1" || ( echo [ERRO] retaguarda-service nao subiu em 3 min: ele e o dono do banco ^(Flyway^) e sem ele os outros falham com "missing table". Veja a janela dele. & pause & exit /b 1 )
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
echo [AVISO] A producao nao usa este script nem Docker. Rode gerar-producao.bat e depois producao\iniciar.bat ^(so Java 21 e PostgreSQL^).
pause
exit /b 1

:fim
echo Tudo iniciado em janelas separadas. Use parar.bat para encerrar.
endlocal
