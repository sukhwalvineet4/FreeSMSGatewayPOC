@echo off
title Free Mobile SIM SMS Gateway POC Launcher
color 0A
cls
echo =========================================================================
echo 🚀 FREE MOBILE SIM SMS GATEWAY POC
echo =========================================================================
echo.
echo  📱 Mobile Phone Connected: Vivo T1 (Ready to Send Real SIM SMS)
echo  ⚡ Web Dashboard: http://localhost:3000
echo.
echo =========================================================================

:: 1. Open Browser immediately
start "" "http://localhost:3000"

:: 2. Try starting server (if not already running)
cmd /c "cd /d %~dp0 && node server.js"

echo.
echo =========================================================================
echo  Press any key to close this window.
echo =========================================================================
pause >nul
