#!/bin/bash
cd "$(dirname "$0")/.."

echo "============================================"
echo " Noesis Platform - Stopping All Services"
echo "============================================"
echo ""

# 1. Stop Java process
echo "[1/2] Stopping Noesis application..."

# Kill any process listening on port 8081 (default port)
if command -v lsof >/dev/null 2>&1; then
    PORT_PIDS=$(lsof -t -i:8081 2>/dev/null)
    if [ -n "$PORT_PIDS" ]; then
        for pid in $PORT_PIDS; do
            kill -9 "$pid" 2>/dev/null
            echo "  Killed PID $pid on port 8081"
        done
    fi
fi

if [ -f .noesis_app.pid ]; then
    APP_PID=$(cat .noesis_app.pid)
    if kill -0 "$APP_PID" 2>/dev/null; then
        kill "$APP_PID"
        echo "  Killed PID $APP_PID"
    fi
    rm .noesis_app.pid
else
    # Fallback to check running Java commands matching 'noesis'
    PIDS=$(pgrep -f "noesis")
    if [ -n "$PIDS" ]; then
        for pid in $PIDS; do
            kill "$pid" 2>/dev/null
            echo "  Killed PID $pid"
        done
    fi
fi
sleep 3
echo "  Stopped."
echo ""

# 2. Stop Docker services
echo "[2/2] Stopping infrastructure services..."
if docker compose down >/dev/null 2>&1; then
    echo "  Docker services stopped."
else
    echo "  Docker compose down failed (maybe already stopped)."
fi
echo ""
echo "============================================"
echo " Noesis has been stopped."
echo " Start again with:  ./noesis-start.sh"
echo "============================================"
