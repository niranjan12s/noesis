@echo off
title Noesis — Starting
cd /d "%~dp0"

echo ============================================
echo  Noesis Platform — Starting All Services
echo ============================================
echo.

REM ── 1. Check Docker ────────────────────────────────────────────────────────
echo [1/5] Checking Docker...
docker info >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo ERROR: Docker is not running. Please start Docker Desktop first.
    pause
    exit /b 1
)
echo   Docker OK
echo.

REM ── 2. Start Docker Compose services ───────────────────────────────────────
echo [2/5] Starting infrastructure services ^(Kafka, OpenSearch, Redis, Postgres^)...
docker-compose up -d 2>&1 | findstr /V "Network.*Created Network.*External"
echo   Waiting for services to become healthy...
:WAIT_LOOP
    set "HEALTHY_COUNT=0"
    for /f %%c in ('docker compose ps ^| findstr "(healthy)" ^| find /c /v ""') do (
        set "HEALTHY_COUNT=%%c"
    )
    if %HEALTHY_COUNT% lss 4 (
        <nul set /p="."
        ping -n 3 127.0.0.1 >nul
        goto WAIT_LOOP
    )
echo   All infrastructure services healthy.
echo.

REM ── 2b. Interactive LLM Setup & Environment Loading ───────────────────────
echo [2b/5] Initializing LLM configuration...
set "PYTHON_CMD="
where py >nul 2>&1
if %ERRORLEVEL% equ 0 (
    set "PYTHON_CMD=py"
) else (
    where python >nul 2>&1
    if %ERRORLEVEL% equ 0 (
        set "PYTHON_CMD=python"
    )
)
if not defined PYTHON_CMD (
    echo ERROR: Python is not installed or not in your PATH. Please install Python.
    pause
    exit /b 1
)

REM Run interactive setup
%PYTHON_CMD% "%~dp0noesis.py" setup
if %ERRORLEVEL% neq 0 (
    echo ERROR: LLM configuration setup failed.
    pause
    exit /b 1
)

REM Load decrypted configuration variables in current process environment
echo Loading LLM environment configuration...
for /f "usebackq tokens=1,* delims==" %%A in (`%PYTHON_CMD% "%~dp0noesis.py" get-env`) do (
    set "%%A=%%B"
)
echo.

REM ── 3. Find Java 21 (Gradle toolchain OR JAVA_HOME OR PATH) ────────────────
echo [3/5] Finding Java 21 runtime...
set "JAVA_CMD="
set "JAVA_HOME_DIR="
for /f "delims=" %%j in ('dir /b /s "%USERPROFILE%\.gradle\jdks\java.exe" 2^>nul') do (
    set "JAVA_CMD=%%j"
    goto JAVA_FOUND
)
if not defined JAVA_HOME goto CHECK_PATH
if not exist "%JAVA_HOME%\bin\java.exe" goto CHECK_PATH
set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
set "JAVA_HOME_DIR=%JAVA_HOME%"
goto JAVA_FOUND

:CHECK_PATH
where java >nul 2>&1
if %ERRORLEVEL% equ 0 set "JAVA_CMD=java"

:JAVA_FOUND
if not defined JAVA_CMD goto JAVA_NOT_FOUND
if not defined JAVA_HOME_DIR (
    for %%i in ("%JAVA_CMD%") do (
        for %%p in ("%%~dpi..") do set "JAVA_HOME_DIR=%%~fp"
    )
)
"%JAVA_CMD%" -version 2>&1 | findstr "21" >nul
if %ERRORLEVEL% equ 0 goto JAVA_VERSION_OK
echo WARNING: Java version may not be 21.
echo   This can cause NoClassDefFoundError if the JAR was compiled with Java 21.
echo   Install JDK 21 and set JAVA_HOME, or let Gradle download it automatically.
:JAVA_VERSION_OK
echo   Using: %JAVA_CMD%
if not defined JAVA_HOME_DIR goto SKIP_SET_HOME
echo   Setting JAVA_HOME=%JAVA_HOME_DIR%
set "JAVA_HOME=%JAVA_HOME_DIR%"
:SKIP_SET_HOME
echo.
goto BUILD_STEP

:JAVA_NOT_FOUND
echo ERROR: Java not found. Install JDK 21 and set JAVA_HOME.
pause
exit /b 1

:BUILD_STEP

REM ── 4. Build JAR ───────────────────────────────────────────────────────────
echo [4/5] Building application...
call .\gradlew.bat bootJar -q
if %ERRORLEVEL% neq 0 (
    echo ERROR: Build failed.
    pause
    exit /b 1
)

for /f "tokens=*" %%j in ('dir /b /s build\libs\*-SNAPSHOT.jar 2^>nul') do (
    if not defined JAR_PATH set "JAR_PATH=%%j"
)
if not defined JAR_PATH (
    echo ERROR: Could not find built JAR.
    pause
    exit /b 1
)
echo   JAR ready: %JAR_PATH%
echo.

REM ── 5. Start Spring Boot ───────────────────────────────────────────────────
echo [5/5] Starting Noesis application...
if not exist logs mkdir logs

start "Noesis-App" "%JAVA_CMD%" -jar "%JAR_PATH%"

echo   Waiting for application to be ready...
:APP_WAIT
    ping -n 3 127.0.0.1 >nul
    curl -sf http://localhost:8081/actuator/health >nul 2>&1
    if %ERRORLEVEL% neq 0 goto APP_WAIT

echo.
echo ============================================
echo  Noesis is UP ^(http://localhost:8081^)
echo  MCP server: mcp_server.py ^(launched by AI client^)
echo ============================================
echo.
echo  Stop with:  noesis-stop.bat
echo  Dashboard:  http://localhost:8081
echo.
title Noesis — Running
