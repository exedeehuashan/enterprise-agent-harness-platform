# P42: Backup-restore drills

Status: generic EAHP operations. `scripts/deploy.sh` has always taken a `pg_dump`
before deploying, but a backup that has never been restored is only a hope. EAHP
now restores its backups on purpose: by hand from a backup file, and automatically
every night from a fresh dump. Each drill checks the result against the live
database.

## Architecture Explanation

- **`scripts/restore.sh --drill [FILE]`** checks a backup file. By default it uses
  the newest `backups/eahp-*.dump`.
  1. Start a throwaway Postgres container with the compose file's image (no
     published port, random superuser password that is never stored).
  2. `pg_restore --exit-on-error` into an empty database.
  3. Check the schema history (latest version, no failed migrations) and count the
     key tables.
  4. Write `backups/drill-<time>.json` (mode 600), then remove the container and
     its volume.

  It exits non-zero when the file does not restore cleanly. It also notes when a
  backup is older than this checkout's migrations, which is normal, since Flyway
  migrates it on start.
- **`scripts/restore.sh --production FILE [--yes]`** really restores.
  1. Ask for confirmation (type `restore`, or pass `--yes`).
  2. Take a **safety backup** of the current database.
  3. Stop the backend so nothing writes.
  4. `pg_restore --clean --if-exists --exit-on-error`.
  5. Start the backend (which applies newer migrations) and check health.

  If the restore fails, it prints the command that restores the safety backup.
  Data volumes are never removed.
- **Automatic drill in EAHP** (`BackupDrills`) runs nightly at 04:15 UTC
  (`eahp.backup-drill.cron`, after retention at 03:30). Approvers can also start
  one with `POST /api/v1/admin/backup-drills`. It uses the QA runner's Docker
  access.
  1. Read the live schema version and the row counts of `agent_tasks`,
     `agent_runs`, `audit_events`, `run_artifacts`, `qa_run_ingestions` and
     `qa_test_results`.
  2. Start a throwaway `postgres:16` container on the stack's network
     (`EAHP_BACKUP_DRILL_NETWORK`), with no published port and
     `no-new-privileges`.
  3. Copy a mode-600 `.pgpass` into it (`docker cp`), run `pg_dump` from inside it
     against the live database, then delete the file in the container and locally.
     The password never appears in a command line.
  4. `createdb` and `pg_restore --exit-on-error` into the throwaway server.
  5. The restored copy must have **the same schema version** and **at least** the
     row counts read before the dump. These tables only grow between the two
     moments, and retention runs earlier.
  6. Always `docker rm -f -v` the container and delete the workspace.
- **Results.**
  - `backup_drills` (V35) records the trigger, status (`PASSED`, `FAILED` or
    `SKIPPED` when Docker or the network is unavailable), schema version,
    expected and restored rows, dump size and error. One drill runs at a time
    (409 otherwise), and a drill interrupted by a restart is closed after an hour.
  - `BACKUP_DRILL_PASSED` and `BACKUP_DRILL_FAILED` are audit events, so webhooks
    and the P41 chat or email notifications announce them.
  - Metrics: `eahp_backup_drill_last_success_seconds` (alert when it is older than
    a day or two) and `eahp_backup_drill_last_failed`.
  - Console: Administration → "Backup drills" lists the drills and has a "Run
    drill now" button.

## Trade-off Analysis

- **A fresh dump, not the deploy backup file.** The backend container cannot see
  the host's `backups/` directory, and a drill of a fresh dump proves the whole
  path: dump, restore and compare. The script covers the files themselves.
- **"At least" row counts.** Exact equality would fail whenever EAHP wrote an
  event between the count and the dump. Rows only disappear through retention,
  which runs at a different time, so fewer rows means data was lost.
- **Throwaway container, not a second database on the live server.** Restoring
  never touches the production server's catalog or resources, and removing the
  container removes every trace.

## Production Considerations

- Requires the QA runner (Docker socket). Without it, drills are `SKIPPED` with
  a reason, never silently missing.
- Copy `backups/` off the host as well. A drill proves that the dump is
  restorable, not that it survives losing the disk.
- V35 adds `backup_drills`.

## Bilingual Interview Q&A

Q: How do you know your backups actually work?

中文：定期演练恢复。每晚在一个临时 Postgres 容器里对生产库做一次新的 pg_dump，再 pg_restore 到空库，校验 schema 版本一致、关键表行数不少于导出前的数量，然后删除容器。结果写表、出指标、发通知。生产恢复脚本会先做安全备份、停后端，再恢复。

English: Rehearse the restore regularly. Every night, a throwaway Postgres
container takes a fresh `pg_dump` of the live database and restores it into an
empty database. The drill checks that the schema version is the same and that key
tables have at least the rows counted before the dump, then removes the container.
Results go to a table, metrics and notifications. The production restore script
takes a safety backup and stops the backend first.

## Verification

- `BackupDrillApiTest` (3), with a fake docker:
  - a drill passes, and the container runs on the configured network without a
    published port;
  - the `.pgpass` has mode 600, escapes `:` and is removed;
  - no password appears in any command;
  - the container is always removed and the workspace deleted;
  - a `BACKUP_DRILL_PASSED` event is written, and the API is approver-only;
  - missing rows and a failed restore fail with reasons and events, and no
    Docker gives `SKIPPED`;
  - only one drill runs at a time, and a stale drill is closed as interrupted.
- `scripts/restore.sh --drill` was checked by hand against a real backup (passed)
  and a truncated copy (failed, exit 1).
- Frontend: drill results (error, dump size) and "Run drill now".

## Observed local results

- `scripts/restore.sh --drill` restored `backups/eahp-20261024T042344Z.dump`
  (1.66 MB) in 3 s: schema V33, all six tables populated (2,352 test results,
  398 audit events, …). The result was written to `backups/drill-*.json`, and no
  container was left behind. A copy cut to 400 kB failed with
  `pg_restore: … end of file` and exit code 1.
- After deploying (V35), `POST /api/v1/admin/backup-drills` passed in 2.5 s: V35,
  a 1,664,056-byte dump, and restored rows equal to the live counts for all six
  tables. No drill container remained. Prometheus shows
  `eahp_backup_drill_last_failed 0` and `eahp_backup_drill_last_success_seconds`.
