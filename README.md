# Enterprise Agent Harness Platform

Java 17 / Spring Boot 3 backend for Agent Task Management, with a React +
TypeScript web console in `frontend/`.

## One-command local deployment

Prerequisites: Docker with Compose v2, curl and openssl. From the repository root:

```sh
cp .env.example .env && chmod 600 .env
# Edit .env: set DB_PASSWORD and three distinct random API tokens.
scripts/deploy.sh                      # backend, console and PostgreSQL
EAHP_QA_RUNNER=on scripts/deploy.sh    # also lets the backend run QA test containers
```

The script validates the Compose files, backs up the database to `backups/` when
it is already running, builds the images, starts everything with health checks,
and smoke-tests the console proxy. Rerun it to upgrade. The console is at
http://127.0.0.1:8088 and the API at http://127.0.0.1:8080/api/v1 (both bound to
localhost only). Data lives in the `postgres_data` and `eahp-qa-work` volumes.

`EAHP_QA_RUNNER=on` adds `docker-compose.qa.yml`, which mounts the Docker socket
into the backend. That is host-level access: enable it only on a machine
dedicated to EAHP. Set `DOCKER_SOCKET_GID` to the socket's group as seen by
containers (0 on Docker Desktop). Without it, QA runs fail with `RUNNER_UNAVAILABLE`.
See [P9 deployment notes](docs/p9-one-command-deploy.md).

## Run the backend from source

Prerequisites: Java 17, Maven 3.6.3+, Docker with Compose, a running Docker daemon, and a random admin API token.
From the repository root:

```sh
cp .env.example .env
# Edit .env and choose your own local DB_PASSWORD before continuing.
set -a
. ./.env
set +a
docker compose up -d --wait postgres
cd backend
# Supply from a private secret source; minimum 32 URL-safe characters.
# export EAHP_SECURITY_ADMIN_TOKEN=...
SERVER_ADDRESS=127.0.0.1 mvn spring-boot:run
```

The application listens on port 8080. Configuration is supplied through `DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`, and optionally `PORT`. Spring Boot does not load
`.env` automatically; export it as shown. Do not commit `.env` or credentials.
Flyway applies versioned SQL migrations on startup; Hibernate validates the
schema and never creates it. PostgreSQL data persists in a named Compose volume.
Use `docker compose stop` from the root to stop PostgreSQL while retaining data.

## Local API identity and roles

Every API call now requires `Authorization: Bearer <token>`. Startup fails if
`EAHP_SECURITY_ADMIN_TOKEN` is absent/invalid. Use a cryptographically random
token (32–256 URL-safe characters); never commit credentials. Optional distinct
`EAHP_SECURITY_OPERATOR_TOKEN` and `EAHP_SECURITY_VIEWER_TOKEN` enable those
identities. No default token, browser session or query-string token is accepted.

Admin can operate and approve; operator can read and submit/edit/cancel but
cannot approve or read approval/audit endpoints; viewer can only read ordinary
resources. This is one local workspace, not multi-tenant authentication or SSO.
Keep the server bound to localhost; remote deployment requires TLS.

For the examples below, set `AUTH_HEADERS` to a private file containing one
`Authorization: Bearer ...` line (mode 600):

```sh
AUTH_HEADERS=/absolute/private/path/admin.headers
```

## API

```sh
curl -H "@$AUTH_HEADERS" -i http://localhost:8080/api/v1/agent-tasks \
  -H 'Content-Type: application/json' \
  -d '{"title":"Analyze report","description":"Prepare a summary"}'
# Replace the value with the id returned by POST.
TASK_ID=00000000-0000-0000-0000-000000000000
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/agent-tasks/$TASK_ID"
```

### List tasks

```sh
curl -H "@$AUTH_HEADERS" 'http://localhost:8080/api/v1/agent-tasks?page=0&size=20&status=PENDING&sort=createdAt,desc'
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
curl -H "@$AUTH_HEADERS" -i "http://localhost:8080/api/v1/agent-tasks/$TASK_ID"
curl -H "@$AUTH_HEADERS" -i -X PATCH "http://localhost:8080/api/v1/agent-tasks/$TASK_ID" \
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
curl -H "@$AUTH_HEADERS" -i -X POST "http://localhost:8080/api/v1/agent-tasks/$TASK_ID/runs" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-001' \
  -d '{"tool":"DEMO_ANALYZE","timeoutSeconds":30}'
# Replace RUN_ID with the returned id.
RUN_ID=00000000-0000-0000-0000-000000000000
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/agent-runs/$RUN_ID"
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/agent-tasks/$TASK_ID/runs?page=0&size=20"
curl -H "@$AUTH_HEADERS" -i -X POST "http://localhost:8080/api/v1/agent-runs/$RUN_ID/cancel"
```

Start returns 202 and a Location for the durable run. The JSON object may be
empty; defaults are DEMO_ANALYZE and 30 seconds. Only those two fields are
accepted. Timeout is an integer 1–300 and includes queued execution time. For
approval-required runs, a separate 30-minute approval window precedes it. The key is
required (1–128 ASCII letters/digits or `._:-`) and scoped to the task.
Same key and normalized request return the original run, even after completion
or later task edits; changing the request returns 409. A new key while a run
is QUEUED/RUNNING returns 409. Missing tasks/runs return 404; invalid requests
return the existing structured 400 response.

The Worker polls PostgreSQL every 500 ms, claims one run atomically and performs
only a pure local length calculation. DEMO_ANALYZE executes directly;
DEMO_REVIEW waits for human approval. Both tools are safe simulations, with no
model, Shell, network or order-writing tools. Run detail includes immutable task input snapshots, status,
deadline, timestamps, attempt count and one persisted step with its output.
History uses page/size only and is sorted by createdAt/id descending.

Run states include QUEUED, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED,
CANCELLED, TIMED_OUT, REJECTED, EXPIRED and NEEDS_REVIEW. A queued or approval-waiting
cancel returns 200/CANCELLED; running cancel returns 202 with cancelRequested
until the Worker acknowledges it. Terminal cancel is an idempotent 200.
Task summary is RUNNING while queued/executing, COMPLETED after success and
FAILED after other terminal outcomes. Run transitions update the task version,
so clients must refresh ETag before editing.

Claims expire after 10 seconds. Recovery retries DEMO_ANALYZE at most once
(two claims total), rejects old claim results and marks a second lost Worker
FAILED/WORKER_LOST. Deadlines end runs as TIMED_OUT. This is bounded recovery,
not an exactly-once guarantee or a hard process-kill mechanism. All future
side-effecting or long-running tools need their own execution policy.
DEMO_REVIEW is not retried automatically; a lost claim requires review.
Disable automatic polling with `EAHP_WORKER_ENABLED=false` for controlled
experiments; default is enabled. See [P2 design and verification](docs/p2-execution-core.md).

### Approval and evidence

Start a run with `{"tool":"DEMO_REVIEW"}` to obtain WAITING_APPROVAL, approvalId
and inputDigest. The admin reviews the run snapshot, toolVersion and target,
then submits a digest-bound decision:

```sh
APPROVAL_ID=00000000-0000-0000-0000-000000000000
# Replace the digest with the exact inputDigest returned for this approval.
curl -H "@$AUTH_HEADERS" -X POST "http://localhost:8080/api/v1/approvals/$APPROVAL_ID/decision" \
  -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","expectedDigest":"<64-character digest>","reasonCode":"REVIEWED"}'
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/approvals/$APPROVAL_ID"
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/approvals?page=0&size=20"
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/audit-events?runId=$RUN_ID"
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/agent-runs/$RUN_ID/invocations"
curl -H "@$AUTH_HEADERS" http://localhost:8080/api/v1/tools
```

Decision accepts APPROVE/REJECT and reasonCode REVIEWED/RISK_NOT_ACCEPTED;
unknown fields are rejected. Already decided or mismatched digests return 409.
Expiry discovered during decision persists EXPIRED and returns 410 with the
approval object; changed policy/input returns 409 with INVALIDATED. Other
validation errors use the existing error shape. Approval is checked again and
consumed atomically just before dispatch. A consumed decision cannot authorize
another attempt. Policy changes, modified inputs and disabled tools cannot
reuse approval. See [P3 design and limits](docs/p3-approval-and-audit.md).

### Model planning (Mock foundation)

```sh
curl -H "@$AUTH_HEADERS" -i -X POST "http://localhost:8080/api/v1/agent-tasks/$TASK_ID/plans" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: plan-001' -d '{}'
# Replace CALL_ID with the returned id.
CALL_ID=00000000-0000-0000-0000-000000000000
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/model-calls/$CALL_ID"
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/agent-tasks/$TASK_ID/plans?page=0&size=20"
curl -H "@$AUTH_HEADERS" http://localhost:8080/api/v1/model-provider
# Admin only:
curl -H "@$AUTH_HEADERS" http://localhost:8080/api/v1/model-budget
```

Plan creation returns 202/Location and an immutable task snapshot. The default
Mock Provider produces a fixed DEMO_REVIEW proposal; it does not understand the
task, call a real model, create runs, approve tools or execute tests. Poll for
QUEUED → RUNNING → VALIDATED/FAILED. VALIDATED means the plan conforms to the
schema and current tool policy; it does not mean the plan is correct or approved.

The body accepts only maxOutputTokens (64–2048, default 512) and timeoutMillis
(100–5000, default 2000). Missing/invalid keys or parameters return 400. Same
Idempotency-Key/options replay the original call, including after task edits or
failure; changed options or a different key while planning is active return 409.
GET history accepts page/size only, with the same bounds as task lists.

Planning has a 60-second queue deadline, per-call timeout, stale-result fencing,
and no automatic retries. Only validated JSON is retained; invalid/raw provider
output and exception details are discarded. Plans allow 1–5 supported local
steps and reject unknown fields, tools, targets, duplicate keys and trailing data.
Token usage is estimated and Mock costUsd is zero. The UTC daily token reservation
budget defaults to 20000 (`EAHP_MODEL_DAILY_TOKEN_BUDGET`); every new call reserves
estimated input plus the full output cap, including failed/expired calls. Replays
do not reserve again; exhaustion returns 429 before provider invocation. This is
a local token safety limit, not an implemented paid-provider spending guarantee.

`EAHP_MODEL_PROVIDER` currently accepts only `mock`; naming another provider
fails startup rather than silently falling back. No external API key is needed.
The planner is enabled by default and can be disabled independently with
`EAHP_PLANNER_ENABLED=false`. Two bounded provider threads and no submission queue
limit stuck calls; timeouts request interruption but cannot guarantee a future
remote provider has stopped or avoided billing. See [P4 design and learning notes](docs/p4-model-planning.md).

### Execute a validated plan

```sh
curl -H "@$AUTH_HEADERS" -i -X POST "http://localhost:8080/api/v1/model-calls/$CALL_ID/executions" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: exec-001' -d '{}'
# Replace EXECUTION_ID with the returned id.
EXECUTION_ID=00000000-0000-0000-0000-000000000000
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/plan-executions/$EXECUTION_ID"
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/agent-tasks/$TASK_ID/plan-executions?page=0&size=20"
curl -H "@$AUTH_HEADERS" -i -X POST "http://localhost:8080/api/v1/plan-executions/$EXECUTION_ID/cancel"
```

An operator turns a VALIDATED plan into a plan execution: 202/Location, with
step 1 launched as an ordinary run and later steps PENDING. The body must be an
empty object. Each step is a separate run created through the normal run start
path, so gated tools such as DEMO_REVIEW wait for their own P3 approval and every
step has its own snapshot, digest, invocation and audit trail. A step launches
only after the previous run COMPLETED; any other terminal run outcome stops the
execution with `STEP_<run status>` and marks later steps SKIPPED. Nothing is
retried or resumed automatically.

Execution and every later launch require the task title/description to still
equal the planning snapshot, the plan to pass current tool policy, and the task
to have no other active run. Violations return 409 at start, or stop a running
execution with TASK_CHANGED, POLICY_CHANGED or TASK_BUSY. A plan can be executed
once; the same key replays, a different key returns 409. Only one plan execution
may be RUNNING per task. Cancel is idempotent: it stops the execution immediately
and cancels the current run through the usual cooperative path. Advancement runs
in the Worker (`EAHP_WORKER_ENABLED`). See [P5 design notes](docs/p5-plan-execution.md).

### Tool plug-ins and artifacts

Tools are code-owned `ToolExecutor` beans; a database definition can disable a tool
but must match its executor's version, target, approval and retry policy exactly.
Runs may pass tool-specific `arguments` (a JSON object validated by the tool, stored
as canonical JSON, and included in idempotency and approval digests):

```sh
curl -H "@$AUTH_HEADERS" "http://localhost:8080/api/v1/agent-runs/$RUN_ID/artifacts"
curl -H "@$AUTH_HEADERS" -OJ "http://localhost:8080/api/v1/artifacts/$ARTIFACT_ID/content"
```

A tool reports success or failure with an output summary and artifacts (reports,
logs). Failures end the run FAILED with their evidence kept; they are never shown as
success. Artifacts carry SHA-256 digests and are always served as downloads. Long
tools keep their lease alive through a heartbeat. See
[P7 design notes](docs/p7-tool-plugins-and-artifacts.md).

### QA test suites (方案 4)

Projects under test and their predefined suites are registered as data
(`GET /api/v1/qa/projects`; `online-shop` is seeded). Run a suite at a branch, tag
or commit; the run records the resolved commit and returns `summary.json`,
`junit-reports.zip` and `console.log` as artifacts:

```sh
curl -H "@$AUTH_HEADERS" -i -X POST "http://localhost:8080/api/v1/agent-tasks/$TASK_ID/runs" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: qa-001' \
  -d '{"tool":"QA_RUN_SUITE","timeoutSeconds":1800,"arguments":{"project":"online-shop","suite":"backend-unit"}}'
```

Suites that need Docker access use `QA_RUN_PRIVILEGED_SUITE`, which requires
approval. The backend needs `git`, the Docker CLI and access to a Docker daemon
(`backend/Dockerfile` builds such an image). Settings: `EAHP_QA_WORKDIR`, and
`EAHP_QA_VOLUME` to share the work directory with test containers through a named
volume. See [P8 design notes](docs/p8-qa-suite-runner.md).

**Pull request triggers.** With `EAHP_GITHUB_POLL_ENABLED=true`, EAHP polls GitHub
every 5 minutes for open pull requests of registered projects and runs the
enabled trigger suites (`qa_pr_triggers`) at each new head commit. A newer push
cancels the previous commit's run; fork pull requests are recorded but never run.
`GET /api/v1/qa/pull-requests` and the console's Pull requests page show what
happened. EAHP only reads from GitHub. See
[P11 design notes](docs/p11-github-pr-triggers.md).

**Suites declared in the repository.** A project can declare its suites in
`.eahp/qa-suites.json` (path configurable). EAHP reads the file at the exact commit
being tested; a repository suite overrides a registry suite with the same key, and
registry suites remain the fallback. Each project's policy (allowed images, Docker
access, network egress, maximum time) limits every suite, and the resolved
definition is stored in the run, so approvals cover the exact command.
`GET /api/v1/qa/projects/{key}/suites?ref=` lists what is available at a ref. See
[P12 design notes](docs/p12-repository-declared-suites.md).

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

## Web console

`frontend/` is a React + TypeScript console for tasks, runs, plans, plan
executions, approvals and audit trails. Sign in with an API token; it is kept
only in that browser tab's sessionStorage and sent only in the Authorization
header. The console calls `GET /api/v1/me` to learn the caller's roles and hides
actions the role cannot perform; the backend still enforces every permission.

```sh
cd frontend
npm ci
npm run dev        # http://localhost:5173, proxies /api to EAHP_API_ORIGIN (default http://127.0.0.1:8080)
npm test
npm run build
```

For a local deployment, the Docker image serves the built console with nginx
and proxies `/api` to the `eahp-backend` container on the Compose network:

```sh
docker build -t eahp-console frontend
docker run -d --name eahp-console --network enterprise-agent-harness-platform_default \
  -p 127.0.0.1:8088:8080 eahp-console
```

See [P6 design notes](docs/p6-web-console.md).

QA engineers can run a registered test suite from a task's **Run test suite**
form, read the outcome, counts and failing tests on the run page, and download
reports and logs (fetched with the Authorization header, never a token in a URL).
The **Test projects** page lists projects and suites. See
[P10 design notes](docs/p10-console-qa.md).

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

GitHub Actions runs `mvn verify` with Java 17, and the console's typecheck, tests
and build with Node 22, on pushes and pull requests.
Authentication, task execution, LLM integrations, list/update/delete APIs,
deployment, and production hardening are outside Issue #1.
