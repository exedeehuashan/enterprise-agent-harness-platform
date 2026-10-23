#!/usr/bin/env bash
# One-command local deployment: validate config, back up the database, build, start, wait for health, smoke test.
# Usage: scripts/deploy.sh            (QA runner off)
#        EAHP_QA_RUNNER=on scripts/deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "Missing .env. Copy .env.example to .env, set every value, then rerun." >&2
  exit 1
fi
requested_qa_runner="${EAHP_QA_RUNNER:-}"
set -a; . ./.env; set +a
# A value given on the command line wins over .env.
EAHP_QA_RUNNER="${requested_qa_runner:-${EAHP_QA_RUNNER:-off}}"

files=(-f docker-compose.yml)
if [ "${EAHP_QA_RUNNER:-off}" = "on" ]; then files+=(-f docker-compose.qa.yml); fi
compose() { docker compose "${files[@]}" "$@"; }

echo "==> Validating configuration"
compose config --quiet

if [ -n "$(compose ps -q postgres 2>/dev/null)" ]; then
  mkdir -p backups && chmod 700 backups
  backup="backups/eahp-$(date -u +%Y%m%dT%H%M%SZ).dump"
  echo "==> Backing up database to $backup"
  compose exec -T postgres pg_dump -U agent_harness -d agent_harness -Fc > "$backup"
  chmod 600 "$backup"
fi

echo "==> Building images"
compose build

echo "==> Starting services"
compose up -d --wait --remove-orphans

echo "==> Smoke test through the console proxy"
console="http://127.0.0.1:${EAHP_CONSOLE_PORT:-8088}"
anonymous=$(curl -s -o /dev/null -w '%{http_code}' "$console/api/v1/me")
# The token is passed on stdin, never on the command line.
identity=$(printf 'Authorization: Bearer %s\n' "$EAHP_SECURITY_ADMIN_TOKEN" | curl -fsS -H @- "$console/api/v1/me")
if [ "$anonymous" != "401" ] || ! grep -q '"name":"admin"' <<<"$identity"; then
  echo "Smoke test failed (anonymous=$anonymous)" >&2
  exit 1
fi
echo "OK: console $console, API $console/api/v1, QA runner ${EAHP_QA_RUNNER:-off}"
