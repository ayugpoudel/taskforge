#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/setup.sh
./gradlew bootJar
docker compose up -d --build --scale worker=2 --wait
