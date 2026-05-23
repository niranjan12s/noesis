@echo off
cd /d "%~dp0.."
.\gradlew.bat bootJar -x test --no-daemon > gradle-output.log 2>&1
exit %ERRORLEVEL%
