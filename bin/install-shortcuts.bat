@echo off
REM Run this once to install shortcuts on your desktop
cd /d "%~dp0.."

set "SCRIPT_DIR=%~dp0.."
set "DESKTOP=%USERPROFILE%\Desktop"

REM ── Create Start shortcut ───────────────────────────────────────────────────
mshta vbscript:CreateObject("WScript.Shell").CreateShortcut("%DESKTOP%\Noesis - Start.lnk").TargetPath("%COMSPEC%").Arguments("/c """"%SCRIPT_DIR%\bin\noesis-start.bat""""").WorkingDirectory("%SCRIPT_DIR%").WindowStyle(7).Save() && echo   Created: Noesis - Start.lnk

REM ── Create Stop shortcut ────────────────────────────────────────────────────
mshta vbscript:CreateObject("WScript.Shell").CreateShortcut("%DESKTOP%\Noesis - Stop.lnk").TargetPath("%COMSPEC%").Arguments("/c """"%SCRIPT_DIR%\bin\noesis-stop.bat""""").WorkingDirectory("%SCRIPT_DIR%").WindowStyle(7).Save() && echo   Created: Noesis - Stop.lnk

REM ── Create Dashboard shortcut ───────────────────────────────────────────────
mshta vbscript:CreateObject("WScript.Shell").CreateShortcut("%DESKTOP%\Noesis - Dashboard.lnk").TargetPath("%WINDIR%\explorer.exe").Arguments("http://localhost:8081").Save() && echo   Created: Noesis - Dashboard.lnk

echo.
echo Shortcuts installed on your desktop.
pause
