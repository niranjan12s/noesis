#!/bin/bash
cd "$(dirname "$0")/.."
mkdir -p logs
./gradlew bootRun > logs/stdout.log 2>&1
