#!/bin/bash
cd "$(dirname "$0")/.."

echo "============================================"
echo " Noesis Platform - Starting All Services"
echo "============================================"
echo ""

# 1. Check Docker
echo "[1/5] Checking Docker..."
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker is not running. Please start Docker Desktop/Daemon first."
    exit 1
fi
echo "  Docker OK"
echo ""

# 2. Start Docker Compose services
echo "[2/5] Starting infrastructure services (Kafka, OpenSearch, Redis, Postgres)..."
docker compose up -d 2>&1
echo "  Waiting for services to become healthy..."
while true; do
    HEALTHY_COUNT=$(docker compose ps 2>/dev/null | grep -c "(healthy)")
    if [ "$HEALTHY_COUNT" -ge 4 ]; then
        break
    fi
    printf "."
    sleep 3
done
echo ""
echo "  All infrastructure services healthy."
echo ""

# 2b. Interactive LLM Setup & Environment Loading
echo "[2b/5] Initializing LLM configuration..."
PYTHON_CMD=""
if command -v python3 >/dev/null 2>&1; then
    PYTHON_CMD="python3"
elif command -v python >/dev/null 2>&1; then
    PYTHON_CMD="python"
fi

if [ -z "$PYTHON_CMD" ]; then
    echo "ERROR: Python is not installed or not in your PATH. Please install Python."
    exit 1
fi

# Run interactive setup
$PYTHON_CMD "$(dirname "$0")/../noesis.py" setup
if [ $? -ne 0 ]; then
    echo "ERROR: LLM configuration setup failed."
    exit 1
fi

# Load decrypted configuration variables in current process environment
echo "Loading LLM environment configuration..."
eval "$($PYTHON_CMD "$(dirname "$0")/../noesis.py" get-env | sed 's/^/export /')"
echo ""

# 3. Find Java 21 (Gradle toolchain OR JAVA_HOME OR PATH)
echo "[3/5] Finding Java 21 runtime..."
JAVA_CMD=""
JAVA_HOME_DIR=""

# Try Gradle wrapper jdks first
if [ -d "$HOME/.gradle/jdks" ]; then
    JAVA_CMD=$(find "$HOME/.gradle/jdks" -name "java" -type f -perm -111 2>/dev/null | head -n 1)
fi

if [ -z "$JAVA_CMD" ] && [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
    JAVA_HOME_DIR="$JAVA_HOME"
fi

if [ -z "$JAVA_CMD" ]; then
    if command -v java >/dev/null 2>&1; then
        JAVA_CMD="java"
    fi
fi

if [ -z "$JAVA_CMD" ]; then
    echo "ERROR: Java not found. Install JDK 21 and set JAVA_HOME."
    exit 1
fi

# Resolve JAVA_HOME_DIR if not set
if [ -z "$JAVA_HOME_DIR" ] && [ "$JAVA_CMD" != "java" ]; then
    JAVA_HOME_DIR=$(dirname "$(dirname "$JAVA_CMD")")
fi

if "$JAVA_CMD" -version 2>&1 | grep -q "21"; then
    echo "  Java 21 verified."
else
    echo "WARNING: Java version may not be 21."
    echo "  This can cause NoClassDefFoundError if the JAR was compiled with Java 21."
    echo "  Install JDK 21 and set JAVA_HOME, or let Gradle download it automatically."
fi

echo "  Using: $JAVA_CMD"
if [ -n "$JAVA_HOME_DIR" ]; then
    echo "  Setting JAVA_HOME=$JAVA_HOME_DIR"
    export JAVA_HOME="$JAVA_HOME_DIR"
fi
echo ""

# 4. Build JAR
echo "[4/5] Building application..."
./gradlew bootJar -q
if [ $? -ne 0 ]; then
    echo "ERROR: Build failed."
    exit 1
fi

JAR_PATH=$(find build/libs/ -name "*-SNAPSHOT.jar" -type f 2>/dev/null | head -n 1)
if [ -z "$JAR_PATH" ]; then
    echo "ERROR: Could not find built JAR."
    exit 1
fi
echo "  JAR ready: $JAR_PATH"
echo ""

# 5. Start Spring Boot
echo "[5/5] Starting Noesis application..."
mkdir -p logs

# Start application as a background process and save PID
"$JAVA_CMD" -jar "$JAR_PATH" >/dev/null 2>&1 &
APP_PID=$!
echo $APP_PID > .noesis_app.pid

echo "  Waiting for application to be ready..."
while true; do
    sleep 3
    if curl -sf http://localhost:8081/actuator/health >/dev/null 2>&1; then
        break
    fi
done

echo ""
# Print high-fidelity CYAN banner
CYAN='\033[96m'
RESET='\033[0m'
echo -e "${CYAN}"
if [ -f "src/main/resources/banner.txt" ]; then
    cat "src/main/resources/banner.txt"
else
    echo "N    N   OOO   EEEEE  SSSSS  IIIII  SSSSS"
    echo "NN   N  O   O  E      S        I    S"
    echo "N N  N  O   O  EEEE   SSSS     I    SSSS"
    echo "N  N N  O   O  E          S    I       S"
    echo "N   NN   OOO   EEEEE  SSSSS  IIIII  SSSSS"
    echo ""
    echo "       :: Noesis Semantic Graph Memory System :: v1.0.0"
fi
echo -e "${RESET}"

echo "============================================"
echo " Noesis is UP (http://localhost:8081)"
echo " MCP server: mcp_server.py (launched by AI client)"
echo "============================================"
echo ""
echo " Stop with:  ./noesis-stop.sh"
echo " Dashboard:  http://localhost:8081"
echo ""
