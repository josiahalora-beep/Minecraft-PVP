@echo off
setlocal
cd /d %~dp0

if not exist spigot-1.8.8.jar (
  echo Missing spigot-1.8.8.jar. Run setup-windows.ps1 first.
  pause
  exit /b 1
)

if not exist java8-home.txt (
  echo Missing java8-home.txt. Rerun setup-windows.ps1 so the project can save your Java 8 path.
  pause
  exit /b 1
)

set /p JAVA8_HOME=<java8-home.txt
set "JAVA8=%JAVA8_HOME%\bin\java.exe"

if not exist "%JAVA8%" (
  echo Java 8 was saved at:
  echo   %JAVA8_HOME%
  echo but java.exe is no longer there. Rerun setup-windows.ps1.
  pause
  exit /b 1
)

echo Using Java 8: %JAVA8%
"%JAVA8%" -Xms1G -Xmx4G -jar spigot-1.8.8.jar nogui
pause
