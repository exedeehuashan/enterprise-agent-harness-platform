# Enterprise Agent Harness Platform

Java 17 / Spring Boot 3 backend for Agent Task Management. `frontend/` is reserved
for a future React + TypeScript application.

## Run locally

Prerequisites: Java 17, Maven 3.6.3+, Docker with Compose, and a running Docker daemon.
From the repository root:

```sh
cp .env.example .env
# Edit .env and choose your own local DB_PASSWORD before continuing.
set -a
. ./.env
set +a
docker compose up -d --wait postgres
cd backend
mvn spring-boot:run
```

The application listens on port 8080. Configuration is supplied through `DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`, and optionally `PORT`. Spring Boot does not load
`.env` automatically; export it as shown. Do not commit `.env` or credentials.
Flyway applies `V1__create_agent_tasks.sql` on startup; Hibernate validates the
schema and never creates it. PostgreSQL data persists in a named Compose volume.
Use `docker compose stop` from the root to stop PostgreSQL while retaining data.

## API

```sh
curl -i http://localhost:8080/api/v1/agent-tasks \
  -H 'Content-Type: application/json' \
  -d '{"title":"Analyze report","description":"Prepare a summary"}'
# Replace the value with the id returned by POST.
TASK_ID=00000000-0000-0000-0000-000000000000
curl "http://localhost:8080/api/v1/agent-tasks/$TASK_ID"
```

### List tasks

```sh
curl 'http://localhost:8080/api/v1/agent-tasks?page=0&size=20&status=PENDING&sort=createdAt,desc'
```

Collection GET returns a stable JSON envelope:

```json
{"items": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0}
```

Items contain the same fields as task detail. Defaults: page 0, size 20,
`createdAt,desc`. Size is 1–100; page is nonnegative and page × size must fit a
signed integer. Optional status accepts PENDING, RUNNING, COMPLETED, FAILED
(case sensitive). Sort accepts only `createdAt,asc` or `createdAt,desc`, with
UUID as the tie breaker in the same direction. Unknown, repeated, empty, or
invalid parameters return structured 400 errors. Empty/out-of-range pages
return 200. Offset pagination does not provide a snapshot across concurrent
insertions.

### Edit tasks safely

Create and detail responses include a numeric `version` and a quoted `ETag`
header. Collection items also include `version`. Retrieve the latest task, then
send its ETag in `If-Match` when editing:

```sh
curl -i "http://localhost:8080/api/v1/agent-tasks/$TASK_ID"
curl -i -X PATCH "http://localhost:8080/api/v1/agent-tasks/$TASK_ID" \
  -H 'Content-Type: application/json' -H 'If-Match: "0"' \
  -d '{"title":"Updated task"}'
```

PATCH returns 200 with the task and current ETag. Only `title` and `description`
are editable. Omitted fields stay unchanged; `description: null` clears it.
Title must be a nonblank string (max 255); description is a string or null
(max 10,000). Empty objects, unknown fields, invalid types and malformed JSON
return 400. Missing `If-Match` returns 428; stale versions, including concurrent
write conflicts, return 412. A valid request for a missing task returns 404.
Only one quoted nonnegative numeric version is accepted; wildcard, weak,
unquoted, multi-value and overflowing versions return 400. An effective update
increments version and advances updatedAt; unchanged values leave both intact.
Creation time, ID and status cannot be edited. On 412, reload and reconcile the
changes before resubmitting. V2 adds version=0 for existing records; V1 remains
unchanged.

### Run a task asynchronously

```sh
# Each new execution needs a new key. Reusing the same key/request replays it.
curl -i -X POST "http://localhost:8080/api/v1/agent-tasks/$TASK_ID/runs" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-001' \
  -d '{"tool":"DEMO_ANALYZE","timeoutSeconds":30}'
# Replace RUN_ID with the returned id.
RUN_ID=00000000-0000-0000-0000-000000000000
curl "http://localhost:8080/api/v1/agent-runs/$RUN_ID"
curl "http://localhost:8080/api/v1/agent-tasks/$TASK_ID/runs?page=0&size=20"
curl -i -X POST "http://localhost:8080/api/v1/agent-runs/$RUN_ID/cancel"
```

Start returns 202 and a Location for the durable run. The JSON object may be
empty; defaults are DEMO_ANALYZE and 30 seconds. Only those two fields are
accepted. Timeout is an integer 1–300 and includes queue time. The key is
required (1–128 ASCII letters/digits or `._:-`) and scoped to the task.
Same key and normalized request return the original run, even after completion
or later task edits; changing the request returns 409. A new key while a run
is QUEUED/RUNNING returns 409. Missing tasks/runs return 404; invalid requests
return the existing structured 400 response.

The Worker polls PostgreSQL every 500 ms, claims one run atomically and performs
only a pure local length calculation. There are no model, Shell, network or
order-writing tools. Run detail includes immutable task input snapshots, status,
deadline, timestamps, attempt count and one persisted step with its output.
History uses page/size only and is sorted by createdAt/id descending.

Run states: QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT. A queued
cancel returns 200/CANCELLED; running cancel returns 202 with cancelRequested
until the Worker acknowledges it. Terminal cancel is an idempotent 200.
Task summary is RUNNING while queued/executing, COMPLETED after success and
FAILED after other terminal outcomes. Run transitions update the task version,
so clients must refresh ETag before editing.

Claims expire after 10 seconds. Recovery retries this pure tool at most once
(two claims total), rejects old claim results and marks a second lost Worker
FAILED/WORKER_LOST. Deadlines end runs as TIMED_OUT. This is bounded recovery,
not an exactly-once guarantee or a hard process-kill mechanism. All future
side-effecting or long-running tools need their own execution policy.
Disable automatic polling with `EAHP_WORKER_ENABLED=false` for controlled
experiments; default is enabled. See [P2 design and verification](docs/p2-execution-core.md).

### Create and retrieve behavior

POST returns `201 Created`, a relative `Location` header, and JSON with `id`,
`title`, `description`, `status`, `createdAt`, `updatedAt`, and `version`. IDs are UUIDs;
timestamps are UTC ISO-8601 strings. New tasks have status `PENDING` and equal
creation/update timestamps. The other task statuses are `RUNNING`,
`COMPLETED`, and `FAILED`; run lifecycle transitions update these summaries.
GET returns `200 OK` with the same fields, or `404 Not Found` for an unknown UUID.

`title` must be nonblank and at most 255 characters. `description` is optional,
nullable, and at most 10,000 characters. Invalid fields, malformed JSON, missing
bodies, and malformed UUIDs return `400 Bad Request` with a stable error shape:

```json
{
  "timestamp": "2026-10-16T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/api/v1/agent-tasks",
  "fieldErrors": [{"field": "title", "message": "must not be blank"}]
}
```

`fieldErrors` is empty for errors unrelated to field validation. Unexpected
errors return a generic 500 response; internal exception details stay in logs.

## Test and build

```sh
cd backend
mvn test
mvn verify
```

JUnit 5 and MockMvc exercise the full application with a temporary embedded
PostgreSQL server, actual Flyway migrations, and Hibernate schema validation.
Tests cover persisted creation/lookup, optional descriptions, validation limits,
missing tasks, malformed UUIDs/bodies, and unsupported HTTP methods. Docker and
an external database are not needed for tests. The test runner must be a non-root
user because PostgreSQL refuses to run as root, and the platform must support
the embedded PostgreSQL native binaries (Linux CI uses Ubuntu).

GitHub Actions runs `mvn verify` with Java 17 on pushes and pull requests.
Authentication, task execution, LLM integrations, list/update/delete APIs,
deployment, and production hardening are outside Issue #1.

## Approved Issue automation

Issue #3 adds a guarded `agent-ready` label workflow using disposable hosted
Ubuntu runners, an isolated Codex worker/verifier, and a separate GitHub App PR
publisher. It requires trusted authorization and never merges or deploys.
See [setup, security boundaries, smoke tests, and troubleshooting](docs/issue-automation.md)
and [actual local testing evidence](docs/issue-automation-test-results.md).
Local security tests need no App or OpenAI credentials:

```sh
python3 -I -B -m unittest discover -s .github/automation/tests -v
```
