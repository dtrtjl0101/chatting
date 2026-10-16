#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f deploy/.env ]]; then
  echo "deploy/.env is missing. Copy deploy/.env.example to deploy/.env first." >&2
  exit 1
fi

COMPOSE=(docker compose --project-name chatting-test --env-file deploy/.env -f deploy/compose.test.yml)
read_env() {
  awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; found=1; exit } END { if (!found) exit 1 }' deploy/.env
}

DB_PASSWORD="$(read_env MYSQL_TEST_PASSWORD || true)"
if [[ -z "$DB_PASSWORD" ]]; then
  echo "MYSQL_TEST_PASSWORD is empty in deploy/.env." >&2
  exit 1
fi

echo "Starting local MySQL and Redis..."
"${COMPOSE[@]}" up -d --wait db redis

export SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD"
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3307/chatting_test?serverTimezone=UTC"
export SPRING_DATASOURCE_USERNAME=chatting_test
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6380
export SERVER_PORT="${SERVER_PORT:-8080}"

for key in GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET KAKAO_CLIENT_ID KAKAO_CLIENT_SECRET; do
  value="$(read_env "$key" || true)"
  if [[ -n "$value" ]]; then export "$key=$value"; fi
done

echo "Using test DB at 127.0.0.1:3307/chatting_test and Redis at 127.0.0.1:6380"
echo "Starting Spring Boot at http://localhost:${SERVER_PORT}"
./gradlew :app:bootRun
