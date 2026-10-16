## Goal
Build the first independently testable backend module for the Enterprise Agent Harness Platform monorepo. This is a bootstrap development task intended to validate GitHub Issue → Codex CLI → Pull Request → CI.

## Tech stack
Java 17, Spring Boot 3, Maven, PostgreSQL, Flyway, JUnit 5, MockMvc. Reserve `frontend/` for a future React + TypeScript application.

## Scope
1. Initialize `backend/` with a Spring Boot application and a clear package structure.
2. Define AgentTask: UUID id, title, description, status (PENDING, RUNNING, COMPLETED, FAILED), createdAt and updatedAt.
3. Implement POST /api/v1/agent-tasks and GET /api/v1/agent-tasks/{id}.
4. Persist tasks using Spring Data JPA with PostgreSQL and Flyway migrations.
5. Validate required fields and return structured errors for invalid input and missing tasks.
6. Add a Docker Compose PostgreSQL service and configuration examples without committing secrets.
7. Add README startup/testing instructions, automated tests, and basic GitHub Actions CI.

## Acceptance criteria
- `mvn test` passes and covers task creation, lookup, validation and 404 responses.
- Database migration applies successfully to PostgreSQL.
- API requests return JSON with stable field names and appropriate HTTP status codes.
- The application starts locally following the README.
- No external LLM integration, deployments or production credentials are introduced in this issue.
- Implement only the scoped task; include a summary, testing evidence and remaining limitations in a PR.

## Codex implementation instructions
Review the task before writing code; work on a feature branch; use minimal, maintainable changes; write tests and report actual results. Do not merge the PR or deploy automatically.

## Definition of Done
Code and documentation are committed to a PR, CI checks are configured, and a human can review the changes.
