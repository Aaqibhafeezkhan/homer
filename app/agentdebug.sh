#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/agentdebug/build/libs/agentdebug-0.1.0-all.jar"
if [ ! -f "$JAR" ]; then
  echo "agentdebug JAR not found. Build it first:"
  echo "  cd $SCRIPT_DIR && ./gradlew :agentdebug:shadowJar"
  exit 1
fi
exec java --enable-native-access=ALL-UNNAMED -jar "$JAR" "$@"
