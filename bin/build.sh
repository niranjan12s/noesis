#!/bin/bash
cd "$(dirname "$0")/.."
./gradlew bootJar -x test --no-daemon > gradle-output.log 2>&1
exit $?
