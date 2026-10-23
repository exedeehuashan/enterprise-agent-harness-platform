# P18: Retention and storage housekeeping

Status: storage stays bounded as runs accumulate (PR, scheduled and manual), without
losing the evidence trail. Generic EAHP capability.

## Architecture Explanation

- **Artifact expiry** (`RetentionService`): artifact content older than
  `EAHP_RETENTION_ARTIFACT_DAYS` (default 30) is replaced by an empty value and
  `expired_at` is set. Name, media type, size, SHA-256 and the invocation link stay,
  so a run still proves what it produced. Downloads of expired content return
  **410 Gone**. The artifacts of the **latest QA run per project, suite and ref** are
  never expired, so current release evidence is always downloadable.
- **Test history window**: `qa_test_results` rows older than
  `EAHP_RETENTION_TEST_RESULT_DAYS` (default 180) are deleted; per-run ingestion
  records (counts, category) remain.
- **Schedule**: `RetentionJob` runs nightly at 03:30 UTC with the worker
  (`eahp.retention.cron`). Approvers can apply it now via
  `POST /api/v1/admin/retention/run`; each application is recorded in
  `retention_runs` (actor, windows, artifacts expired, bytes freed, rows deleted).
- **Storage report**: `GET /api/v1/admin/storage` (APPROVER): size of the main
  tables, live and expired artifacts, live artifact bytes, last retention run.
- **Ingestion** skips expired artifacts instead of failing.
- **Backups**: `scripts/deploy.sh` keeps the newest `EAHP_BACKUP_KEEP` (default 10)
  dumps in `backups/`.
- **Console**: run pages mark expired artifacts (with the expiry time and a note
  that the SHA-256 still identifies them) instead of offering a download; the Test
  projects page has a Storage and retention panel for approvers.

## Trade-off Analysis

Expiring content but keeping metadata is a middle ground: deleting artifact rows
would free slightly more space but break the link between a run and its evidence.
The digests remain verifiable against any copy kept elsewhere.

Content stays in PostgreSQL; the retention window, not an object store, bounds it.
An object store remains the next step for large evidence (videos, traces).

## Production Considerations

- PostgreSQL reuses freed space after (auto)vacuum; the database file does not
  shrink immediately.
- Retention windows of less than a day are rejected at startup.
- Older manual backups outside `backups/` (for example from earlier manual
  deployments) are not touched.
- V17 adds `run_artifacts.expired_at` and `retention_runs`.

## Bilingual Interview Q&A

Q: Why keep artifact metadata after deleting the content?

中文：运行记录需要证明当时产生了什么证据。保留名称、大小和 SHA-256，链路不断；如果别处还有副本，也能用摘要核对。

English: A run must still show what evidence it produced. Keeping name, size and
SHA-256 preserves that link, and any copy kept elsewhere can be verified against
the digest.

Q: Why never expire the latest run per suite and ref?

中文：发布门禁依据的是最新结果，它的报告和日志在任何时候都要能下载查看。

English: The release gate relies on the latest result; its reports and logs must
always be available.

## Verification

`QaSuiteToolApiTest` (24 tests) adds a retention case: older artifacts expire while
the latest run per suite and ref keeps them, bytes freed are reported, expired
content returns 410 with metadata intact, old per-test history is deleted,
ingestion records keep their counts, the storage report and manual run are
approver-only, and a run ingested after its artifacts expired is ingested without
error. Frontend: 20 tests, including the expired badge and the storage panel.
Related backend suites: 111 pass.

## Observed local results

Deployed with `scripts/deploy.sh`, which also pruned `backups/` to the newest 10
dumps (4 removed). An approver-triggered retention run expired nothing (all
evidence is younger than 30 days) and was recorded; the storage report showed 36
live artifacts (about 744 KiB) and `run_artifacts` as the largest table; operators
get 403.
