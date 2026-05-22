@echo off
cd /d "%~dp0"
.\gradlew.bat test --no-daemon > test-output.log 2>&1
exit %ERRORLEVEL%
