@echo off
cd /d "C:\Users\Niranjan\Documents\GitHub\neosis"
.\gradlew.bat bootJar -x test --no-daemon > gradle-output.log 2>&1
exit %ERRORLEVEL%
