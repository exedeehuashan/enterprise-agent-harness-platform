# Implement Agent Task Management API

Closes #1.

Creates a Java 17 / Spring Boot 3 Maven backend with POST and GET endpoints at
`/api/v1/agent-tasks`. Tasks persist in PostgreSQL with UUID IDs, an initial
`PENDING` status, and UTC timestamps. Flyway owns the schema; Hibernate validates
it. Requests validate title and description limits, and failures return a stable
JSON error structure.

Includes local PostgreSQL Compose configuration, environment examples, startup
and API documentation, a reserved frontend directory, and GitHub Actions Maven
verification. MockMvc integration tests run against temporary PostgreSQL without
requiring Docker.

## Validation

Executed locally with Java 17.0.19 and Maven 3.9.11 on 2026-10-17:

```text
mvn -B -ntp -Dmaven.repo.local=/tmp/issue-1-m2 -f backend/pom.xml test
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Flyway successfully applied migration V1 to PostgreSQL 14.15; Hibernate schema
validation and the full Spring application test context started successfully.
Compose configuration validation and `git diff --check` also passed.
`mvn -DskipTests package` subsequently passed and produced the executable Spring
Boot JAR; tests were skipped only in that separate packaging run.

## Limitations and delivery

- Docker Compose startup requires a running Docker daemon. The local daemon was
  unavailable during implementation, so the Compose PostgreSQL 16 startup path
  was not tested. Automated tests used embedded PostgreSQL 14.15.
- Embedded PostgreSQL tests require a supported platform and a non-root user.
- Authentication, task execution, status transitions, and production deployment
  are outside the issue's scope.
- Changes remain local for review. No push, remote PR, merge, or deployment was
  performed. Creating the remote PR requires a later authorized push.
