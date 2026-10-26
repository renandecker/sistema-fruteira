@echo off
cd /d "%~dp0"
schtasks /Create /F /SC DAILY /ST 02:00 /TN "Fruteira Backup" /TR "\"%~dp0backup.bat\"" /RL HIGHEST
if errorlevel 1 ( echo [ERRO] Rode como Administrador ) else ( echo Backup diario agendado para 02:00 ^(Agendador de Tarefas: Fruteira Backup^) )
