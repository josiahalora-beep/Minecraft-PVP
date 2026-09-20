@echo off
setlocal
cd /d %~dp0
if not exist spigot-1.8.8.jar (
  echo Missing spigot-1.8.8.jar. Run setup-windows.ps1 first.
  pause
  exit /b 1
)
java -Xms1G -Xmx4G -jar spigot-1.8.8.jar nogui
pause
