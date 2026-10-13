@echo off
rem Agenda o backup todo dia as 02:00 no Agendador de Tarefas do Windows (rode uma vez, como administrador se pedir).
cd /d "%~dp0"
schtasks /Create /F /SC DAILY /ST 02:00 /TN "Fruteira Backup" /TR "\"%~dp0backup.bat\"" && echo Agendado: todo dia as 02:00 (Agendador de Tarefas ^> Fruteira Backup)
