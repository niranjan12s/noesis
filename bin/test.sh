#!/bin/bash
cd "$(dirname "$0")/.."
./gradlew test --no-daemon > test-output.log 2>&1
exit $?
