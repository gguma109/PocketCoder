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
echo PocketCoder - Tailscale remote access or trusted private LAN
 echo NEVER forward TCP port 8765 on your router to the Internet.
echo ============================================================
if not exist "%~dp0pocketcoder-server.jar" (
  echo Download pocketcoder-desktop-server from GitHub Actions and extract all files together.
  pause
  exit /b 1
)
if /i "%~1"=="--tailscale" (
  "%JAVA%" -Dsun.java2d.uiScale=1 -jar "%~dp0pocketcoder-server.jar" --bind=tailscale --port=8765
  pause
  exit /b
)
ipconfig
echo For access away from home, enter the IPv4 shown under Tailscale.
echo Both PC and phone must be connected to the same Tailscale network.
set "BIND_ADDRESS="
set /p "BIND_ADDRESS=Enter this PC's Tailscale or private LAN IPv4 address: "
if not defined BIND_ADDRESS exit /b 1
"%JAVA%" -Dsun.java2d.uiScale=1 -jar "%~dp0pocketcoder-server.jar" "--bind=%BIND_ADDRESS%" --port=8765
pause
