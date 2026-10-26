@echo off
cd /d "%~dp0"
chcp 65001 >nul
where java >nul 2>nul || ( echo [ERRO] Java 21 nao encontrado no PATH. Instale o JRE/JDK 21: https://adoptium.net & pause & exit /b 1 )
java -jar fruteira-launcher.jar backup %*
set "COD=%ERRORLEVEL%"
if not "%COD%"=="0" if /i "backup"=="iniciar" pause
exit /b %COD%
