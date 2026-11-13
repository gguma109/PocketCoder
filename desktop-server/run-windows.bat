@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "JAVA=java"
where java >nul 2>nul
if errorlevel 1 (
  if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
  ) else (
    echo Java 17+ is required. Install JDK 17 or Android Studio.
    pause
    exit /b 1
  )
)
echo ============================================================
echo PocketCoder - trusted private WiFi only
 echo NEVER forward TCP port 8765 on your router to the Internet.
echo ============================================================
if not exist "%~dp0pocketcoder-server.jar" (
  echo Download pocketcoder-desktop-server from GitHub Actions and extract all files together.
  pause
  exit /b 1
)
ipconfig
set "BIND_ADDRESS="
set /p "BIND_ADDRESS=Enter this PC's private LAN IPv4 address shown above: "
if not defined BIND_ADDRESS exit /b 1
"%JAVA%" -jar "%~dp0pocketcoder-server.jar" "--bind=%BIND_ADDRESS%" --port=8765
pause
