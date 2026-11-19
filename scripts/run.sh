#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env
set +a
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; fi
export TF_ROLE="${1:-api}"
if [[ "$TF_ROLE" != api ]]; then export TF_PORT="${TF_PORT:-8081}"; fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar build/libs/taskforge-0.1.0.jar
