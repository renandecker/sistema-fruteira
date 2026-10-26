@echo off
chcp 65001 >nul
rem Gera producao\ (e opcionalmente o ZIP). Uso: gerar-producao.bat [--zip] [--sem-jars] [--com-testes]
rem Dev: JDK 21, Maven, Node. Servidor: Java 21 + PostgreSQL.
setlocal
cd /d "%~dp0"
set DEST=producao
set ZIP=0
set JARS=1
set TESTES=-DskipTests
set KC_VER=26.0.8
:args
if "%~1"=="" goto go
if "%~1"=="--zip" set ZIP=1
if "%~1"=="--sem-jars" set JARS=0
if "%~1"=="--com-testes" set TESTES=
shift
goto args
:go
for %%c in (java node npm) do where %%c >nul 2>nul || (echo X '%%c' nao encontrado & exit /b 1)
if "%JARS%"=="1" where mvn >nul 2>nul || (echo X 'mvn' nao encontrado & exit /b 1)
for %%d in (servicos logs backups run) do if not exist %DEST%\%%d mkdir %DEST%\%%d
echo ^> Front...
pushd pdv-web
if not exist node_modules call npm install --silent
set APP_ENV=producao
call npm run build --silent || (popd & echo X build do front falhou & exit /b 1)
popd
if exist %DEST%\web rmdir /s /q %DEST%\web
xcopy /e /i /q pdv-web\dist %DEST%\web >nul
echo ^> Launcher...
if not exist launcher\build\tmp mkdir launcher\build\tmp
javac -encoding UTF-8 --release 21 -d launcher\build\tmp launcher\src\br\com\fruteira\launcher\*.java || exit /b 1
> launcher\build\manifest.txt echo Main-Class: br.com.fruteira.launcher.Main
jar --create --file %DEST%\fruteira-launcher.jar --manifest launcher\build\manifest.txt -C launcher\build\tmp . || exit /b 1
if "%JARS%"=="1" (
  for %%p in (catalogo-service:catalogo estoque-service:estoque vendas-service:vendas retaguarda-service:retaguarda tef-agent:tef-agent) do for /f "tokens=1,2 delims=:" %%a in ("%%p") do (
    echo ^> %%a...
    pushd %%a
    call mvn -q package %TESTES% || (popd & echo X build de %%a falhou & exit /b 1)
    popd
    if exist %DEST%\servicos\%%b rmdir /s /q %DEST%\servicos\%%b
    xcopy /e /i /q %%a\target\quarkus-app %DEST%\servicos\%%b >nul
  )
)
if not exist %DEST%\keycloak\bin\kc.bat (
  echo ^> Keycloak %KC_VER%...
  curl -fsSL -o keycloak.zip https://github.com/keycloak/keycloak/releases/download/%KC_VER%/keycloak-%KC_VER%.zip || (echo X download do Keycloak falhou & exit /b 1)
  tar -xf keycloak.zip -C %DEST% || exit /b 1
  if exist %DEST%\keycloak rmdir /s /q %DEST%\keycloak
  ren %DEST%\keycloak-%KC_VER% keycloak
  del keycloak.zip
)
if not exist %DEST%\keycloak\data\import mkdir %DEST%\keycloak\data\import
copy /y keycloak\fruteira-realm.json %DEST%\keycloak\data\import\ >nul
copy /y producao-modelo\* %DEST%\ >nul
copy /y .env.example %DEST%\.env.example >nul
if exist certificado.pfx if not exist %DEST%\certificado.pfx copy certificado.pfx %DEST%\ >nul
echo OK Pasta pronta: %CD%\%DEST%
if "%ZIP%"=="1" (
  if exist fruteira-producao.zip del fruteira-producao.zip
  tar -a -cf fruteira-producao.zip -C %DEST% .
  echo OK fruteira-producao.zip criado
)
