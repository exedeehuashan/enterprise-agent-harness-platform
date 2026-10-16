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

POST returns `201 Created`, a relative `Location` header, and JSON with `id`,
`title`, `description`, `status`, `createdAt`, and `updatedAt`. IDs are UUIDs;
timestamps are UTC ISO-8601 strings. New tasks have status `PENDING` and equal
creation/update timestamps. The other reserved statuses are `RUNNING`,
`COMPLETED`, and `FAILED`; this issue provides no status transition endpoint.
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
