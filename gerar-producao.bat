@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "DEST=producao"
for %%C in (java mvn node npm) do ( where %%C >nul 2>nul || ( echo [ERRO] %%C nao encontrado no PATH & pause & exit /b 1 ) )
if not exist "%DEST%\servicos" mkdir "%DEST%\servicos"
if not exist "%DEST%\logs" mkdir "%DEST%\logs"
if not exist "%DEST%\backups" mkdir "%DEST%\backups"

if exist "%DEST%\.env" (
  echo Mantendo %DEST%\.env existente
) else (
  powershell -NoProfile -Command "$r={-join((48..57+65..90+97..122|Get-Random -Count 24|%%{[char]$_}))}; $t={-join((48..57+65..90+97..122|Get-Random -Count 40|%%{[char]$_}))}; (Get-Content .env.example) -replace '^APP_ENV=.*','APP_ENV=producao' -replace '^KEYCLOAK_ADMIN_PASSWORD=.*',('KEYCLOAK_ADMIN_PASSWORD='+(&$r)) -replace '^KEYCLOAK_DB_PASS=.*',('KEYCLOAK_DB_PASS='+(&$r)) -replace '^DB_PASS=.*',('DB_PASS='+(&$r)) -replace '^#TEF_TOKEN=.*',('TEF_TOKEN='+(&$t)) | Set-Content -Encoding ASCII '%DEST%\.env'"
  if errorlevel 1 ( echo [ERRO] Nao foi possivel gerar o .env & pause & exit /b 1 )
  echo .env criado com senhas aleatorias ^(revise KEYCLOAK_URL, FRONT_URL e dominios^)
)

echo Compilando o front...
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%DEST%\.env") do set "%%A=%%B"
pushd pdv-web
if not exist node_modules call npm install
set "APP_ENV=producao"
call npm run build || ( popd & echo [ERRO] build do front falhou & pause & exit /b 1 )
popd
if exist "%DEST%\web" rmdir /s /q "%DEST%\web"
xcopy /e /i /q /y pdv-web\dist "%DEST%\web" >nul

for %%S in (catalogo-service:catalogo estoque-service:estoque vendas-service:vendas retaguarda-service:retaguarda tef-agent:tef-agent) do (
  for /f "tokens=1,2 delims=:" %%N in ("%%S") do (
    echo Compilando %%N...
    pushd %%N
    call mvn -q package -DskipTests || ( popd & echo [ERRO] build de %%N falhou & pause & exit /b 1 )
    popd
    if not exist "%%N\target\quarkus-app\quarkus-run.jar" ( echo [ERRO] jar de %%N nao gerado & pause & exit /b 1 )
    if exist "%DEST%\servicos\%%O" rmdir /s /q "%DEST%\servicos\%%O"
    xcopy /e /i /q /y "%%N\target\quarkus-app" "%DEST%\servicos\%%O" >nul
  )
)

powershell -NoProfile -Command "(Get-Content docker-compose.prod.yml) -replace './pdv-web/dist','./web' | Set-Content -Encoding UTF8 '%DEST%\docker-compose.prod.yml'"
for %%D in (keycloak database nginx\templates nginx\snippets) do if exist "%DEST%\%%D" rmdir /s /q "%DEST%\%%D"
xcopy /e /i /q /y keycloak "%DEST%\keycloak" >nul
xcopy /e /i /q /y nginx\templates "%DEST%\nginx\templates" >nul
xcopy /e /i /q /y nginx\snippets "%DEST%\nginx\snippets" >nul
if not exist "%DEST%\nginx\certs" mkdir "%DEST%\nginx\certs"
if not exist "%DEST%\database" mkdir "%DEST%\database"
copy /y database\pre_cadastro_frutas_verduras.sql "%DEST%\database\" >nul
copy /y producao-modelo\* "%DEST%\" >nul
copy /y ssl.sh "%DEST%\" >nul & copy /y ssl.bat "%DEST%\" >nul

echo.
echo Pasta pronta: %CD%\%DEST%
echo Copie para o servidor, revise o .env e rode iniciar.bat ^(veja LEIA-ME.txt^).
echo TEF_TOKEN do .env deve ser o mesmo informado em Configuracoes nos caixas.
endlocal
