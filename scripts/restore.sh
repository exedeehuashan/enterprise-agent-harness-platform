#!/usr/bin/env bash
# Restore an EAHP database backup (made by scripts/deploy.sh, pg_dump custom format).
#
#   scripts/restore.sh --drill [BACKUP]           restore into a throwaway Postgres container and verify it (default:
#                                                 the newest backups/eahp-*.dump); nothing in the running stack changes
#   scripts/restore.sh --production BACKUP [--yes]
#                                                 replace the live database with BACKUP: takes a safety backup first,
#                                                 stops the backend while restoring, then starts it and checks health
#
# A drill writes its result to backups/drill-<time>.json and exits non-zero when the backup does not restore cleanly.
# Data volumes are never removed; the production restore replaces the database's contents, not its volume.
set -euo pipefail
cd "$(dirname "$0")/.."

mode="${1:-}"; shift || true
case "$mode" in --drill|--production) ;; *) sed -n '2,12p' "$0" >&2; exit 2 ;; esac
backup="${1:-}"; [ $# -gt 0 ] && shift
confirmed=no; [ "${1:-}" = "--yes" ] && confirmed=yes

if [ -z "$backup" ]; then
  [ "$mode" = "--production" ] && { echo "A production restore needs an explicit backup file." >&2; exit 2; }
  backup="$(ls -1 backups/eahp-*.dump 2>/dev/null | sort | tail -n 1 || true)"
  [ -n "$backup" ] || { echo "No backups/eahp-*.dump found; run scripts/deploy.sh first or pass a file." >&2; exit 1; }
fi
[ -f "$backup" ] || { echo "Backup not found: $backup" >&2; exit 1; }
image="$(sed -n 's/^    image: \(postgres:[^ ]*\)$/\1/p' docker-compose.yml | head -n 1)"; image="${image:-postgres:16}"
latest_migration="$(ls backend/src/main/resources/db/migration | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -n 1)"

if [ "$mode" = "--drill" ]; then
  name="eahp-restore-drill-$$"
  cleanup() { docker rm -f -v "$name" >/dev/null 2>&1 || true; }
  trap cleanup EXIT
  started=$(date -u +%s)
  echo "==> Drill: restoring $backup into a throwaway $image container"
  # The container has no published port and a random superuser password that is never stored.
  docker run -d --name "$name" -e POSTGRES_PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -dc A-Za-z0-9)" "$image" >/dev/null
  for _ in $(seq 1 60); do docker exec "$name" pg_isready -U postgres -q 2>/dev/null && break; sleep 1; done
  docker exec "$name" pg_isready -U postgres -q
  docker cp "$backup" "$name:/tmp/backup.dump" >/dev/null
  docker exec "$name" createdb -U postgres agent_harness
  if ! docker exec "$name" pg_restore -U postgres -d agent_harness --no-owner --no-privileges --exit-on-error /tmp/backup.dump; then
    status=FAILED; error="pg_restore failed"
  else
    status=PASSED; error=""
  fi
  q() { docker exec "$name" psql -U postgres -d agent_harness -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
  version="$(q "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
  failed_migrations="$(q "SELECT count(*) FROM flyway_schema_history WHERE NOT success")"
  counts=""
  for t in agent_tasks agent_runs audit_events run_artifacts qa_run_ingestions qa_test_results; do
    counts="$counts\"$t\":$(q "SELECT count(*) FROM $t" || echo null),"
  done
  if [ "$status" = PASSED ] && { [ -z "$version" ] || [ "${failed_migrations:-0}" != "0" ]; }; then status=FAILED; error="schema history missing or has failed migrations"; fi
  note=""
  if [ -n "$version" ] && [ "$version" -lt "$latest_migration" ]; then note="backup is at schema V$version; this checkout migrates to V$latest_migration on start"; fi
  finished=$(date -u +%s)
  result="backups/drill-$(date -u +%Y%m%dT%H%M%SZ).json"
  umask 077
  printf '{"backup":"%s","bytes":%s,"status":"%s","error":"%s","schemaVersion":%s,"latestMigration":%s,"rows":{%s},"durationSeconds":%s,"note":"%s"}\n' \
    "$backup" "$(stat -c %s "$backup")" "$status" "$error" "${version:-null}" "$latest_migration" "${counts%,}" "$((finished - started))" "$note" > "$result"
  echo "==> Drill $status in $((finished - started)) s: schema V${version:-?}, rows {${counts%,}} → $result"
  [ -n "$note" ] && echo "    note: $note"
  [ "$status" = PASSED ]
  exit $?
fi

# --production
set -a; . ./.env; set +a
files=(-f docker-compose.yml)
if [ "${EAHP_QA_RUNNER:-off}" = "on" ]; then files+=(-f docker-compose.qa.yml); fi
compose() { docker compose "${files[@]}" "$@"; }
if [ "$confirmed" != yes ]; then
  echo "This replaces the live EAHP database with $backup."
  read -r -p "Type 'restore' to continue: " answer
  [ "$answer" = restore ] || { echo "Cancelled."; exit 1; }
fi
mkdir -p backups && chmod 700 backups
safety="backups/eahp-pre-restore-$(date -u +%Y%m%dT%H%M%SZ).dump"
echo "==> Safety backup of the current database to $safety"
compose exec -T postgres pg_dump -U agent_harness -d agent_harness -Fc > "$safety"
chmod 600 "$safety"
echo "==> Stopping the backend so nothing writes during the restore"
compose stop backend
echo "==> Restoring $backup"
if ! compose exec -T postgres pg_restore -U agent_harness -d agent_harness --clean --if-exists --no-owner --exit-on-error < "$backup"; then
  echo "Restore failed. The database may be partly restored; restore the safety backup with:" >&2
  echo "  scripts/restore.sh --production $safety --yes" >&2
  exit 1
fi
echo "==> Starting the backend (it applies any newer migrations)"
compose up -d --wait backend console
curl -fsS "http://127.0.0.1:${EAHP_BACKEND_PORT:-8080}/actuator/health" >/dev/null && echo "OK: restored $backup; safety backup kept at $safety"
