#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env
set +a
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null)}"
export TF_ROLE="${1:-api}"
if [[ "$TF_ROLE" != api ]]; then export TF_PORT="${TF_PORT:-8081}"; fi
exec "$JAVA_HOME/bin/java" -jar build/libs/taskforge-0.1.0.jar
