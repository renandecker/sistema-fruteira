@echo off
setlocal
cd /d "%~dp0"
if not exist ".env" ( echo [ERRO] .env nao encontrado & exit /b 1 )
for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do set "%%A=%%B"
if not exist "nginx\certs\live\fruteira" mkdir "nginx\certs\live\fruteira"
set "ACAO=%1"
if /i "%ACAO%"=="letsencrypt" goto le
if /i "%ACAO%"=="renovar" goto renovar
if /i "%ACAO%"=="local" goto local
echo Uso: ssl.bat letsencrypt ^| renovar ^| local
exit /b 1

:le
where docker >nul 2>nul || ( echo [ERRO] Docker nao encontrado no PATH ^(abra o Docker Desktop^) & exit /b 1 )
docker info >nul 2>nul || ( echo [ERRO] Docker nao esta em execucao. Inicie o Docker Desktop e tente de novo & exit /b 1 )
if "%FRONT_DOMAIN%"=="" ( echo [ERRO] Defina FRONT_DOMAIN no .env & exit /b 1 )
if "%KEYCLOAK_DOMAIN%"=="" ( echo [ERRO] Defina KEYCLOAK_DOMAIN no .env & exit /b 1 )
if "%LE_EMAIL%"=="" ( echo [ERRO] Defina LE_EMAIL no .env & exit /b 1 )
echo %FRONT_DOMAIN%%KEYCLOAK_DOMAIN%%LE_EMAIL% | findstr /i "exemplo" >nul && ( echo [ERRO] Troque os dominios/e-mail de exemplo no .env & exit /b 1 )
echo Emitindo certificado Let's Encrypt para %FRONT_DOMAIN% e %KEYCLOAK_DOMAIN%
echo ^(requisitos: DNS dos dois dominios apontando para este servidor e portas 80/443 liberadas no firewall/roteador^)
docker compose -f docker-compose.prod.yml stop nginx >nul 2>nul
docker run --rm -p 80:80 -v "%CD%\nginx\certs:/etc/letsencrypt" certbot/certbot certonly --standalone --cert-name fruteira -d %FRONT_DOMAIN% -d %KEYCLOAK_DOMAIN% --email %LE_EMAIL% --agree-tos --no-eff-email --non-interactive
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" ( echo. & echo [ERRO] Falha ao emitir o certificado ^(codigo %RC%^). Confira DNS, porta 80 livre e os dominios no .env & exit /b %RC% )
if not exist "nginx\certs\live\fruteira\fullchain.pem" ( echo [ERRO] Certificado nao encontrado em nginx\certs\live\fruteira & exit /b 1 )
docker compose -f docker-compose.prod.yml up -d nginx >nul 2>nul
echo.
echo Certificado emitido com sucesso. No .env use USE_NGINX=true, KEYCLOAK_URL=https://%KEYCLOAK_DOMAIN% e FRONT_URL=https://%FRONT_DOMAIN%, depois rode iniciar.bat.
echo Renovacao: agende "ssl.bat renovar" no Agendador de Tarefas do Windows ^(1x por semana^).
exit /b 0

:renovar
docker compose -f docker-compose.prod.yml stop nginx >nul 2>nul
docker run --rm -p 80:80 -v "%CD%\nginx\certs:/etc/letsencrypt" certbot/certbot renew --standalone
docker compose -f docker-compose.prod.yml up -d nginx >nul 2>nul
exit /b 0

:local
docker run --rm -v "%CD%\nginx\certs:/certs" alpine/openssl req -x509 -newkey rsa:2048 -nodes -days 365 -keyout /certs/live/fruteira/privkey.pem -out /certs/live/fruteira/fullchain.pem -subj "/CN=%FRONT_DOMAIN%" -addext "subjectAltName=DNS:%FRONT_DOMAIN%,DNS:%KEYCLOAK_DOMAIN%"
echo.
echo Certificado AUTOASSINADO gerado. Para testar localmente:
echo  1) hosts (C:\Windows\System32\drivers\etc\hosts): 127.0.0.1 %FRONT_DOMAIN% %KEYCLOAK_DOMAIN%
echo  2) no .env: OIDC_TLS_VERIFICATION=none
echo  3) aceite o aviso do navegador nos dois dominios
exit /b 0
