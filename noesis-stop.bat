@echo off
title Noesis — Stopping
cd /d "%~dp0"

echo ============================================
echo  Noesis Platform — Stopping All Services
echo ============================================
echo.

REM ── 1. Stop Java process ───────────────────────────────────────────────────
echo [1/2] Stopping Noesis application...
for /f "tokens=2 delims==" %%i in ('wmic process where "name='java.exe' and commandline like '%%noesis%%'" get processid /value 2^>nul ^| find "="') do (
    taskkill /f /pid %%i >nul 2>&1
    echo   Killed PID %%i
)
ping -n 3 127.0.0.1 >nul
echo   Stopped.
echo.

REM ── 2. Stop Docker services ────────────────────────────────────────────────
echo [2/2] Stopping infrastructure services...
docker-compose down >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo   Docker services stopped.
) else (
    echo   Docker compose down failed ^(maybe already stopped^).
)
echo.
echo ============================================
echo  Noesis has been stopped.
echo  Start again with:  noesis-start.bat
echo ============================================
title Noesis — Stopped
pause
