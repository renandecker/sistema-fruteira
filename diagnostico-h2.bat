@echo off
chcp 65001 >nul
rem Mostra a CAUSA REAL do erro "Acquisition timeout / IO Exception 90028": abre o banco H2 de desenvolvimento direto e imprime a excecao completa.
rem Rode com TUDO parado (parar.bat). Precisa que os servicos ja tenham sido iniciados uma vez (o jar do H2 fica em %USERPROFILE%\.m2).
setlocal
cd /d "%~dp0"
if exist .env for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do set "%%A=%%B"
if "%H2_ARQUIVO%"=="" ( set "H2_ARQUIVO=%~dp0data/fruteira" & echo %~dp0| findstr /i "onedrive" >nul && set "H2_ARQUIVO=%LOCALAPPDATA:\=/%/Fruteira/data/fruteira" )
echo Arquivo: %H2_ARQUIVO%.mv.db
for /f "delims=" %%J in ('powershell -NoProfile -Command "Get-ChildItem \"$env:USERPROFILE\.m2\repository\com\h2database\h2\" -Recurse -Filter h2-*.jar | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName"') do set "H2JAR=%%J"
if "%H2JAR%"=="" ( echo [ERRO] jar do H2 nao encontrado em %USERPROFILE%\.m2: rode iniciar.bat uma vez. & exit /b 1 )
echo Jar: %H2JAR%
dir "%H2_ARQUIVO:/=\%*" 2>nul
echo ---- tentando abrir (SEM AUTO_SERVER) ----
java -cp "%H2JAR%" org.h2.tools.Shell -url "jdbc:h2:file:%H2_ARQUIVO%" -user sa -password "" -sql "select 'ABRIU OK' as resultado, count(*) as tabelas from information_schema.tables"
echo ---- fim (envie esta saida inteira) ----
endlocal
