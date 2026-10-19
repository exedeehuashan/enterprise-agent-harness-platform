# P2: Durable asynchronous execution core

## Architecture Explanation

The HTTP start transaction locks the task, resolves idempotency before checking
active-run conflicts, saves the input snapshot and step, and updates the task
summary/version. A partial unique index enforces one active run per task.

The scheduled Worker uses a PostgreSQL SKIP LOCKED claim, commits RUNNING with
a lease and random token, performs a pure fixed calculation outside the database
transaction, then publishes the outcome in another transaction. HTTP start does
not execute the tool. Claim tokens fence results from expired or superseded
owners; run/step outcomes and task summary update atomically. Read endpoints use
a repeatable-read transaction so the run and step snapshot agree.

V3 creates agent_runs and run_steps; prior migrations remain untouched. Runtime
execution uses JdbcTemplate and Spring transactions alongside the existing JPA
task module. Direct task summary updates increment the same optimistic version
used by task editing.

## Trade-off Analysis

A database queue avoids a broker for this local MVP. The cost is polling, row
locking and recovery logic in the application. Task-row locking serializes starts
for one task; distinct tasks can be claimed independently. The default scheduler
runs one small tool at a time in this application. There is no throughput or HA
claim; test coverage of simultaneous claims is not a multi-node production trial.

One persisted logical step is sufficient for this fixed-tool workflow. Its
attemptCount is retained on the run, but retrying overwrites the step's attempt
start/output fields. Per-attempt evidence and multi-step planning belong to the
next design expansion. Snapshot title/description may contain user data and are
returned only through this local development API, not logged by the Worker.

## Production Considerations

- Idempotency is task-scoped and bound to the normalized tool/timeout request.
  Snapshot changes do not change an existing key's meaning. Keys are retained
  with runs; no retention/deletion API is provided.
- The deadline includes queued time. Success after the deadline becomes
  TIMED_OUT. Cancellation is cooperative, not a claim that a process was killed.
  An accepted cancellation wins if recorded before finalization obtains the task
  lock; already terminal outcomes stay terminal.
- Lease duration is 10 seconds; the tool must be pure and quick. At most one
  recovery retry is allowed; there is no heartbeat for long jobs. Expired owners
  cannot publish, but duplicate pure calculations can overlap after a lease loss.
  Never add side effects or arbitrary code to this Worker without a new policy.
- Failure exposes TOOL_FAILED, WORKER_LOST or DEADLINE_EXCEEDED, not raw tool
  exceptions. Only run IDs are emitted in the tool-failure warning.
- Authentication, approvals, tool registration, NEEDS_REVIEW for uncertain
  external side effects, resource-isolated testing and model integrations remain
  future work. Runtime tools cannot be selected beyond DEMO_ANALYZE.
- Run history is bounded to 100 items but loads one step per item; no scalability
  claim is made. Large deployments need query-plan, retention and polling review.
- Before a local migration, retain a database dump and the prior runnable JAR.
  An old JAR can still use the expanded schema, but rolling it back does not
  process pending runs. Do not delete data volumes as a recovery shortcut.

## Verification

The regression suite preserves the original API tests and listing tests.
The existing V1-to-V2 migration regression now uses an independent target schema,
rather than dropping columns/history from the shared integration schema; its
original preservation assertions remain. A test-only property disables automatic
polling, and execution tests explicitly drive the Worker against embedded
PostgreSQL. Production polling remains enabled.

Coverage includes durable start/replay, conflicts, immutable input snapshots,
concurrent same/different-key starts, concurrent claims, queued/running cancel,
deadlines, sanitized tool failure, retry bounds, stale claim fencing, history,
parameter errors and V2-to-V3 preservation of old task data/version. Lease and
deadline tests manipulate test database timestamps instead of relying on sleeps.

## Bilingual Interview Q&A

Q: Why is an in-memory async method insufficient?

中文：进程退出会丢失内存队列和执行状态。先持久化运行、再领取执行，重启后才能判断哪些任务待执行或需要恢复。

English: A process crash loses an in-memory queue. Persisting runs before
claiming work allows the system to find queued work and recover interrupted runs.

Q: Does a lease guarantee exactly-once execution?

中文：不保证。租约过期后，新旧 Worker 可能短暂重复计算；领取令牌只能拦截旧结果。副作用需要工具层幂等或人工恢复。

English: No. A lease can expire while the old Worker is still computing.
A fencing token prevents stale result publication; external effects still need
idempotency or manual recovery.

## Hands-on Verification

1. Create a task; retain its ID.
2. POST its runs endpoint with Idempotency-Key and `{}`; expect 202/QUEUED.
3. Poll Location; expect COMPLETED and one output-bearing step.
4. Reuse the same key; expect the same run ID and no new execution.
5. Reuse the key with a different timeout; expect 409.
6. View task history and updated task summary/version.
7. Cancellation may already find a terminal run because this Demo tool is fast;
   use integration tests for deterministic cancellation and failure scenarios.

GitHub publication follows a dedicated feature branch and reviewable PR. Merge
and branch deletion require user instructions; preserve historical branches.

## Observed local results

Maven verify: 102 tests passed, zero failures/errors/skips (72 prior assertions
and 30 run cases). Actual local deployment applied V3 with V1/V2 still valid.
A dedicated smoke task returned 202/QUEUED, progressed to COMPLETED with one
step, replayed the original run ID, rejected a changed request with 409, and
returned task summary COMPLETED/version 2. The entire run response, including
step output, remained unchanged after a backend-container restart. Prior task
data remained present. This restart check verifies persisted completed results;
interrupted-claim recovery is covered by deterministic integration tests.
