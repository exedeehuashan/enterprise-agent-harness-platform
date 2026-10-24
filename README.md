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

**Failure triage and test history.** Finished QA runs are ingested from their
stored reports into per-test history. Each failure gets a category
(for example `ENVIRONMENT_DOCKER`, `ASSERTION`, `TIMEOUT`), an explanation and a
history label (`FIRST_SEEN`, `NEW_FAILURE`, `PERSISTENT`, `FLAKY`), shown on the run
page and via `GET /api/v1/qa/runs/{runId}/triage` and `GET /api/v1/qa/tests/history`.
See [P13 design notes](docs/p13-test-history-and-triage.md).

**Change impact.** For each pull request commit EAHP diffs against the PR's merge
base and reports the changed files, the affected Maven modules (including modules
that depend on changed ones), the test classes in scope and warnings such as
changed modules without tests: `GET /api/v1/qa/pull-requests/{id}/impact`, or the
Impact toggle on the console's Pull requests page. See
[P14 design notes](docs/p14-change-impact-analysis.md).

**Impact-based test selection.** A suite that declares `"selection": "maven-modules"`
runs on pull requests with only the affected modules and their dependencies
(`-pl … -am`), is skipped as `NOT_AFFECTED` when no module changed, and runs in full
whenever the impact is uncertain (build files, `.mvn/`, the suites file, too many
changes). Manual runs may pass `modules`. The selection is bound into the run and
the gate notes when a suite ran only some modules. See
[P19 design notes](docs/p19-impact-based-test-selection.md).

**Path-based selection and frontend suites.** `"selection": "paths"` with a `paths`
list runs a suite on a pull request only when the change touches those paths (or
the suites file), for any build system. online-shop's `frontend-unit` (Vitest with
JUnit output in a Node image) uses it, alongside `backend-unit`'s module selection.
See [P20 design notes](docs/p20-path-selection-and-frontend-suite.md).

**Test quarantine.** Approvers quarantine a failing test with a reason for at most
30 days (`/api/v1/qa/admin/quarantines`, audited). A suite that fails only in
quarantined tests passes the gate with a note, provided it ran at least as many
tests as its last passing run; the tests still run and are recorded. See
[P21 design notes](docs/p21-test-quarantine.md).

**Commit QA report.** `GET /api/v1/qa/projects/{key}/report?ref=` returns a
Markdown summary of a commit: gate verdict, each suite's latest run and scope,
failures with triage, and pull request selection and impact. Untrusted text is
escaped so it can be posted as-is. The Quality page shows and copies it. See
[P22 design notes](docs/p22-commit-qa-report.md).

**Commit comparison.** `GET /api/v1/qa/projects/{key}/compare?base=&head=` lists,
per suite, the tests that newly fail, were fixed, still fail, or were added or
removed between two commits. Pull request reports include the comparison with the
PR's base, and the Quality page has a Compare commits form. See
[P23 design notes](docs/p23-commit-comparison.md).

**API tests against environments.** Approvers register test environments (service
name → base URL). Projects declare HTTP test cases in `.eahp/api-tests.json`
(requests, status, header and JSON assertions, captures, variables).
`QA_RUN_API_TESTS` runs a collection against an environment and produces the same
evidence as suite runs, without following redirects or leaving the registered
origins. See [P24 design notes](docs/p24-api-test-execution.md).

**Exit-code suites.** A repository suite with `"results": "exit-code"` (and no
report directories) records its exit code as one test case, so checks without
JUnit output (such as contract scripts) feed history and gates too. online-shop
also runs its Playwright end-to-end tests this way, in the official Playwright
image. See [P25 design notes](docs/p25-exit-code-suites-and-more-suite-types.md).

**Webhooks.** Approvers register webhook URLs for EAHP events (`RUN_*`,
`APPROVAL_*`, `QA_RESULT_INGESTED`, …) under `/api/v1/admin/webhooks`. Events are
delivered in order, at least once, signed with HMAC-SHA256 (`X-EAHP-Signature`),
and retried with backoff. See [P26 design notes](docs/p26-webhooks.md).

**Named API tokens.** Approvers issue a token per person or integration
(`/api/v1/admin/tokens`, roles VIEWER/OPERATOR/APPROVER, optional expiry), so audit
records name who acted. Only a SHA-256 hash is stored; the token is shown once, and
revocation takes effect on the next request. See
[P27 design notes](docs/p27-named-api-tokens.md).

**Metrics and domain modules.** `/actuator/prometheus` (VIEWER token) exposes JVM
and HTTP metrics, plus EAHP gauges (runs by status, queue age, pending approvals,
webhook health, QA outcomes). `/actuator/health` is public. Domain agents are
`DomainModule`s (core, `qa` = 方案 4) listed at `GET /api/v1/modules`; approvers
can switch a domain off, which disables its tools and pauses its jobs. Approver
tools live on the console's Administration page. See
[P28 design notes](docs/p28-metrics-and-domain-modules.md).

**Code coverage.** Suites may list JaCoCo or Cobertura reports
(`"coverage": ["target/site/jacoco/jacoco.xml"]`). Runs record line and branch
totals (`coverage.json`, `qa_coverage`), reports show line coverage, and the gate
can require a minimum per suite (`minLineCoverage`). See
[P29 design notes](docs/p29-code-coverage.md).

**GitHub write-back.** With `EAHP_GITHUB_WRITE_TOKEN` set and write-back switched
on for a project, QA results are posted to its pull requests: `eahp/<suite>` and
`eahp/release-gate` commit statuses, plus one EAHP comment with the QA report,
updated in place. Delivery goes through an outbox with retries. See
[P30 design notes](docs/p30-github-write-back.md).

**Scheduled API tests.** A schedule with an `environment` runs an API test
collection against it, on branch change or on a cron timetable, for example
nightly against UAT. See [P31 design notes](docs/p31-api-test-schedules.md).

**Suites against environments.** A suite can map variables to services
(`"environment": {"ORDER_API": "order-api"}`). It then runs against a registered
environment, whose URLs are bound into the run and passed to the container, for
example online-shop's k6 performance baseline. See
[P32 design notes](docs/p32-suites-against-environments.md).

**AI providers and failure analysis.** `EAHP_AI_PROVIDER` selects `mock` (default),
`anthropic` (Claude through the official SDK, `ANTHROPIC_API_KEY`, default model
`claude-opus-5-5`) or `deepseek` (`DEEPSEEK_API_KEY`). Planning uses the model, and
`POST /api/v1/qa/runs/{id}/ai-analysis` explains a failed QA run from its redacted
evidence (advisory only). All calls share a daily token budget. See
[P33 design notes](docs/p33-ai-providers-and-failure-analysis.md).

**AI-generated API tests.** `POST /api/v1/qa/projects/{key}/ai/api-tests` drafts
an API test collection from an OpenAPI file in the repository. The draft is
validated by the same parser as human-written tests, can run against an
environment, or can be downloaded to commit. See
[P34 design notes](docs/p34-ai-api-test-generation.md). Setting up Claude or DeepSeek: [docs/ai-model-setup.md](docs/ai-model-setup.md).

**Code agent (Claude Code).** `CODE_AGENT_RUN` runs Claude Code headless on an
approver-registered repository at an exact commit, in a hardened container. Every
run needs approval. EAHP records the diff and, where pushing is allowed and a write
token is set, pushes a branch and opens a draft PR (never merged). Build the runner
image with `scripts/build-code-agent.sh`. See [P35 design notes](docs/p35-code-agent.md).

**Per-commit environments.** A project declares Docker Compose templates in
`.eahp/environments.json`. `QA_RUN_ENVIRONMENT_TESTS` (approval) builds and starts
the stack for one exact commit, runs the template's API test collections against
it and always removes it; `QA_START_ENVIRONMENT` leaves it running for its TTL as a
temporary environment that suites and API tests can target. Compose files are
checked first (no privileged or host settings, bind mounts or external networks,
no published ports, no host environment). A pull request trigger whose key is a
template key (`env-pr`) runs it for every new head commit. See
[P36 design notes](docs/p36-ephemeral-environments.md).

**Code agent egress allowlist.** Each code agent run gets its own internal Docker
network with no route out. Its only exit is EAHP's built-in proxy, which tunnels
`CONNECT` to port 443 of allowlisted hosts only (`EAHP_CODE_AGENT_EGRESS_ALLOW`,
default the Anthropic API and the Maven and npm registries) and records every
attempt in `egress.log`. See [P37 design notes](docs/p37-code-agent-egress.md).

**Performance trends.** A suite that declares `"performance": "k6-summary.json"`
(written by `k6 run --summary-export`) records p95, throughput and error rate per
run. The Quality page shows the trend, and the release gate can enforce
`maxP95Ms`, `maxErrorRate` and `maxP95IncreasePercent` (against the median of
recent passing runs). See [P38 design notes](docs/p38-performance-trends.md).

**Coverage trends and new-code coverage.** Coverage suites also keep per-line
data. `GET /api/v1/qa/projects/{key}/new-code-coverage?base=&head=&suite=` reports
how many of the lines a change adds or modifies are covered, and which are not.
The release gate can require `minNewCodeCoverage` per suite (against the default
branch), and the Quality page shows coverage over time. See
[P39 design notes](docs/p39-coverage-trends-and-new-code.md).

**Flaky-test quarantine suggestions.** Every 10 minutes EAHP looks for tests that
passed and failed at the same commit on at least two commits in 14 days. It
proposes quarantining them, with that evidence and a `QA_QUARANTINE_SUGGESTED`
event. Approvers confirm (a 14-day, audited quarantine) or dismiss (snoozed) with
one click on the Quality page. See
[P40 design notes](docs/p40-flaky-quarantine-suggestions.md).

**Notifications.** Webhook subscriptions have a format. They can post events as
signed JSON, or as short messages to DingTalk (钉钉), WeCom (企业微信), Feishu
(飞书) or Slack group robots, with platform signing and error checks, or by email
(`EAHP_SMTP_*`). Add them under Administration → Notifications and use
"Send test". See [P41 design notes](docs/p41-notification-integrations.md).

**Backup-restore drills.** `scripts/restore.sh --drill [FILE]` restores a backup
into a throwaway Postgres container and verifies it.
`scripts/restore.sh --production FILE` restores for real, after a safety backup.
EAHP also drills nightly: a fresh dump is restored in a disposable container and
must match the live schema version and row counts. Results appear under
Administration → Backup drills, in metrics and in notifications. See
[P42 design notes](docs/p42-backup-restore-drills.md).

**Auto-fix failing tests.** A failed suite run can be handed to the code agent
("Fix with code agent", or automatically per project). After approval Claude Code
fixes the code and opens a draft PR. EAHP then runs the same suite on the PR's
commit and reports FIXED, PARTLY_FIXED or NOT_FIXED as a PR comment. See
[P43 design notes](docs/p43-auto-fix-failing-tests.md).

**Release gate and quality dashboard.** Each project lists the suites that must
pass (`qa_quality_gates`). `GET /api/v1/qa/projects/{key}/gate?ref=` returns PASS,
FAIL or PENDING for a commit with reasons, from recorded evidence only;
`GET /api/v1/qa/projects/{key}/dashboard` summarizes pass rates, failing, flaky and
slow tests and pull request gates. The console's Quality page shows both. See
[P15 design notes](docs/p15-quality-gate-and-dashboard.md).

**Registry administration.** Approvers register projects and manage their policy,
registry suites, pull request triggers and release gates through
`/api/v1/qa/admin/**` or the console's Test projects page, without database access.
Every change is validated like a repository suites file and audited with its
before and after state. See [P16 design notes](docs/p16-qa-registry-administration.md).

**Schedules.** `qa_schedules` runs a suite when a branch's head commit changes
(`ON_CHANGE`, for example `main` after a merge) or on a UTC cron timetable (`CRON`,
for example nightly), as the `scheduler` actor, keeping release gates current.
`GET /api/v1/qa/schedules`; approvers manage them under `/api/v1/qa/admin/schedules`
or on the Test projects page. See [P17 design notes](docs/p17-scheduled-qa-runs.md).

**Retention.** Artifact content expires after 30 days (`EAHP_RETENTION_ARTIFACT_DAYS`)
while its name, size and SHA-256 remain (downloads return 410); the latest QA run
per project, suite and ref keeps its artifacts. Per-test history is kept for 180
days. Retention runs nightly; approvers see `GET /api/v1/admin/storage` and can run
`POST /api/v1/admin/retention/run`. The deploy script keeps the newest 10 backups
(`EAHP_BACKUP_KEEP`). See [P18 design notes](docs/p18-retention-and-storage.md).

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
