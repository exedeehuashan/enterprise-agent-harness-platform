# P17: Scheduled and branch-triggered QA runs

Status: 方案 4 "Mode C". Suites run without a person or a pull request: when a
branch's head commit changes (for example after a merge to main), or on a UTC
timetable (for example a nightly regression). The release gate (P15) therefore
stays current for the branches that matter.

## Architecture Explanation

- `qa_schedules` (V16): project, suite, ref, kind (`ON_CHANGE` or `CRON` with a
  six-field Spring cron expression), enabled, the schedule's task, last commit and
  run, last check and error, next CRON slot. online-shop is seeded with
  `backend-unit` ON_CHANGE on `main`.
- `QaScheduleJob` (with the worker, every minute) calls `QaSchedules.runOnce()`:
  - the ref is resolved and the suite resolved at that commit through the same
    resolver as manual runs (P12), so repository-declared suites and project
    policy apply;
  - ON_CHANGE starts a run only when the commit differs from the last one;
  - CRON runs when its slot is due and then plans the next one; the first check
    after creation only plans, so a new schedule never fires immediately;
  - each schedule has one task; while its previous run is still active the check
    is skipped (`PREVIOUS_RUN_ACTIVE`) rather than queued;
  - runs are created by the `scheduler` actor with an idempotency key from the
    schedule, commit and slot; Docker suites still wait for approval.
- Errors (unknown suite, unreachable repository, rejected start) are recorded on
  the schedule and shown, not thrown.
- API: `GET /api/v1/qa/schedules?project=` (VIEWER);
  `POST /api/v1/qa/admin/schedules`, `PUT /api/v1/qa/admin/schedules/{id}/enabled`,
  `DELETE /api/v1/qa/admin/schedules/{id}` (APPROVER, audited in `qa_admin_events`).
- Console: each project on the Test projects page lists its schedules with the
  last commit, the last run (linked) and the next slot; approvers can add, pause,
  resume and delete them.

## Trade-off Analysis

Branch changes are detected by polling (`git ls-remote` once a minute per
ON_CHANGE schedule) instead of push webhooks, for the same reason as P11: no
public endpoint is needed. Cron is limited to at most one firing per 15 minutes,
which keeps polling and runner load predictable.

Skipping a check while the previous run is active, instead of queueing, means a
burst of merges tests the latest commit rather than every intermediate one.

## Production Considerations

- Schedules use UTC.
- A schedule on a Docker suite creates an approval request at every change or
  slot; prefer ON_CHANGE schedules on isolated suites, and nightly CRON for
  heavier suites only where an approver is available.
- V16 adds `qa_schedules`.

## Bilingual Interview Q&A

Q: Why does a new cron schedule not run immediately?

中文：创建时只计划下一个时间点，避免一保存就意外触发运行；按时间表运行才符合"定时"的预期。

English: Creating a schedule only plans its next slot, so saving it never
triggers a surprise run; it runs when the timetable says so.

Q: What happens if the branch changes while the previous run is still going?

中文：这一轮检查会跳过，等上一次运行结束后再测试最新的提交，这样不会堆积很多中间提交的运行。

English: The check is skipped and the latest commit is tested once the previous
run finishes, so intermediate commits do not pile up runs.

## Verification

`QaSuiteToolApiTest` (23 tests) adds: an ON_CHANGE schedule running once per new
commit as `scheduler`, skipping while the previous run is active and reusing its
task; a CRON schedule planning first, running when due and advancing its slot,
with approver-only management, audit and duplicate rejection; six kinds of invalid
schedules; unknown suites recorded as the schedule's error. Frontend: 20 tests,
including the schedules view. Related backend suites: 97 pass.

## Observed local results

The online-shop fix proposed in P12–P15 was merged in online-shop (#51, `54bf8ad`):
Testcontainers tests are tagged `docker`, CI and scripts select by tag, and
`.eahp/qa-suites.json` declares `backend-unit` and `backend-integration`. Before
merging, EAHP verified that branch through the repository-declared suites:
`backend-unit` exit 0 with 42 tests, and `backend-integration` (approved) exit 0
with 41 Docker tests; the branch's gate was PASS.

After deploying P17, the seeded ON_CHANGE schedule saw `main` move to `54bf8ad`
and started `backend-unit` as `scheduler`, resolved from the repository file
(`-DexcludedGroups=docker`). It passed (exit 0, 42 tests, 15 s), and the release
gate for online-shop `main` changed from FAIL to **PASS** without anyone starting a
run: the full loop of change → automatic test → evidence → gate.
