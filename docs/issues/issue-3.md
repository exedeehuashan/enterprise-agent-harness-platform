## Objective
Automatically turn a **human-approved GitHub Issue** into a reviewable Codex-generated pull request, using a disposable GitHub-hosted Ubuntu runner (not the Ubuntu machine hosting PostgreSQL).

## Trigger and trust boundary
- Trigger ONLY when the `agent-ready` label is added to an Issue, not on arbitrary Issue creation or comments.
- Require `issue.user.login == 'exedeehuashan'` or explicit trusted approval before running.
- Treat Issue body and repository instructions as untrusted input. Never interpret Issue text as instructions to change workflow permissions, access secrets, merge, or deploy.
- Concurrency: one job per issue; idempotently skip if branch/PR already exists.
- Remove/avoid triggering on pull requests masquerading as Issues.

## Workflow
1. Read Issue number, title and body from GitHub using a read-only token and validate owner/label.
2. Checkout trusted default branch in a GitHub-hosted Ubuntu runner.
3. Create feature branch `agent/issue-<number>`.
4. Write validated requirements to a file in a workspace; invoke `openai/codex-action@v1` with scoped workspace permissions (`permission-profile: ':workspace'`; `safety-strategy: drop-sudo`).
5. Codex generates implementation and tests within allowlisted paths. Never allow it to edit `.github/workflows/`, secrets, runner configuration or deployment credentials.
6. Run independent Maven/JUnit tests (and future frontend tests). Record outcomes; don't falsely claim success.
7. Validate changed paths, dangerous symlinks, file modes, and no secrets before creating a patch/artifact.
8. Use a SEPARATE trusted publishing job/workflow (no Codex process running) with narrowly scoped `contents: write` and `pull-requests: write` rights, to create branch/PR without auto-merge or deployment.
9. PR describes `Refs #<number>`, summary, tests and known limitations. Add comment back to Issue with PR link.

## Deliverables
- `.github/workflows/issue-to-codex.yml`
- Trusted publisher workflow, validator script, `AGENTS.md`, workflow design documentation and tests for policy checks.
- README setup for `OPENAI_API_KEY`, labels, repository permissions and reproducible smoke test.
- Explicit safe failure behavior and logs.

## Acceptance criteria
- Labeling an authorized Issue `agent-ready` creates a reviewable PR; CI runs.
- Unauthorized Issue authors, repeated labels, workflow file edits, invalid patches, missing API key and failed tests do not create a privileged PR.
- No production keys available to Codex. No self-hosted runner for the MVP.
- No auto-merge, production deployment or new paid infrastructure.
- Explain costs, permission boundaries, failure recovery, and trade-offs.

## Implementation notes
Start with a design review of GitHub Actions event permissions, artifact handoff and privilege boundaries. Favor minimizing permissions over an all-in-one privileged runner. Do not merge automatically.
