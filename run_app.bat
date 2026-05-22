@echo off
cd /d "%~dp0"
.\gradlew.bat bootRun > logs\stdout.log 2>&1
