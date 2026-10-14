#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
COMPOSE=(docker compose -f deploy/compose.yml)
mkdir -p deploy/state
ACTIVE_FILE=deploy/state/active
ACTIVE=""
if [[ -f "$ACTIVE_FILE" ]]; then ACTIVE="$(cat "$ACTIVE_FILE")"; fi

if [[ "$ACTIVE" == "blue" ]]; then TARGET=green; else TARGET=blue; fi
echo "Building Spring Boot image; deploying to $TARGET slot"
"${COMPOSE[@]}" build app-blue
"${COMPOSE[@]}" up -d --no-deps --no-build "app-$TARGET"

healthy=false
for attempt in $(seq 1 60); do
  state="$(docker inspect --format='{{.State.Health.Status}}' "$("${COMPOSE[@]}" ps -q "app-$TARGET")" 2>/dev/null || true)"
  if [[ "$state" == healthy ]]; then healthy=true; break; fi
  if [[ "$state" == unhealthy ]]; then break; fi
  sleep 2
done

if [[ "$healthy" != true ]]; then
  echo "New slot did not become healthy; leaving current traffic unchanged." >&2
  exit 1
fi

printf 'upstream chat_active { server app-%s:8080; }\n' "$TARGET" > deploy/nginx/active-upstream.conf
if "${COMPOSE[@]}" ps --status running --services | grep -qx proxy; then
  "${COMPOSE[@]}" exec -T proxy nginx -t
  "${COMPOSE[@]}" exec -T proxy nginx -s reload
else
  "${COMPOSE[@]}" up -d proxy
fi
printf '%s\n' "$TARGET" > "$ACTIVE_FILE"
echo "Traffic now uses $TARGET. Previous slot will stop after 30 seconds."
if [[ -n "$ACTIVE" ]]; then
  sleep 30
  "${COMPOSE[@]}" stop "app-$ACTIVE"
fi
echo "Deployment complete. Local URL: http://localhost:8088"
