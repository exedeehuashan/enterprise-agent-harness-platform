# Issue #3 local execution evidence

Executed on 2026-10-17 (Pacific/Auckland). No real OpenAI API key, GitHub App
credential, production secret, GitHub publication, push, merge, or deployment
was used. Publication API calls in unit tests are mocks.

| Check | Actual result |
| --- | --- |
| `python3 -I -B -m unittest discover -s .github/automation/tests -v` | 36 tests passed |
| `actionlint` 1.7.7, release checksum verified, `-shellcheck=` | All workflows accepted; ShellCheck integration disabled because ShellCheck was unavailable |
| `bash -n .github/automation/isolated-maven.sh` | Passed |
| `git diff --check` | Passed |
| Isolated trusted-baseline `mvn clean verify` | 15 tests, 0 failures/errors/skips; executable JAR packaged |
| Isolated offline `mvn -o clean verify` | 15 tests, 0 failures/errors/skips; executable JAR packaged |
| Deliberately failing JUnit candidate, offline container | 16 tests, 1 intentional failure, 0 errors/skips; exit 1; chained publication-eligibility marker absent |
| Reproducible `test-isolated-failure.sh`, with fake host GH_TOKEN | Isolation probe passed; intentional candidate failure correctly rejected (17 tests, 1 intentional failure, 0 errors/skips) |

Containers used Maven 3.9.11 / Temurin Java 17 and the digest pinned in
`isolated-maven.sh`. The actual Flyway migration and Hibernate validation ran
against embedded PostgreSQL 14.15. No local development database was contacted.
Candidate verification used networking disabled, a read-only Maven cache,
non-root execution, dropped capabilities, and a read-only root filesystem.
The executed isolation probe confirmed no GitHub/OpenAI/Actions credential or
control-file environment variables, no Docker socket, non-root identity,
read-only source/cache, `NoNewPrivs: 1`, zero effective capabilities, and loopback-only
network interfaces. The fake host token did not reach the container. Both shell
scripts passed `bash -n`.

## Security test coverage

Credential-free tests exercise authorization by live repository permission,
owner approval for other authors, missing labels, closed Issues, PR-shaped
payloads, content changes, revoked approvals, non-Issue events, denied workflow
reruns, duplicate branches/PRs, and missing credentials.

Patch tests exercise valid reconstruction, new source/tests, protected baseline
tests, forbidden workflow/build/config paths, nested agent instructions, path
traversal, mismatched headers, invalid hunks, duplicate entries, executable modes,
symlinks and parent symlinks, binary/invalid UTF-8 content, size limits, and
synthetic credential patterns. ZIP tests reject traversal, extra entries,
symlinks, executable/special files, malformed archives, wrong run/name, expiration,
and oversize metadata. A download test confirms GitHub Authorization headers are
not forwarded to signed artifact storage.

Mocked publisher tests demonstrate no write API calls for unsafe/tampered output,
stale baselines, changed Issues, duplicates, missing App tokens, and unexpected
callers. The positive mock verifies API-only publication with App-authored
branch/PR writes and a separate workflow-token Issue comment. No candidate
subprocess is allowed in the mocked publication phase.

## Failures found and corrected

- Local passwordless sudo was unavailable. The container harness now uses the
  current non-root UID and needs no sudo locally.
- Docker Desktop could not bind-mount host `/tmp`; the documented local command
  uses ignored `.local-verifier/` storage visible to Docker Desktop. Hosted Ubuntu
  uses its ordinary runner temporary directory.
- Embedded PostgreSQL could not execute from Docker's default noexec tmpfs.
  `/tmp` now explicitly permits execution inside the isolated container while
  retaining `nosuid`, `nodev`, no capabilities, and `no-new-privileges`. A read-only
  UID/GID mapping supports PostgreSQL on numeric runner accounts.
- The corrected warm and offline verification runs passed after these fixes.

## GitHub integration tests — not executed

The following need approved credentials, environment configuration, and the
workflows installed on the default branch: real Codex invocation, GitHub event
authorization/permission APIs, App installation token issuance/revocation,
artifact upload/download on Actions, actual branch/PR/comment creation,
cross-job authorization binding, and independent App-triggered PR CI delivery.

No claim is made that a GitHub-hosted workflow ran or a PR was created. Follow
the [credentialed smoke-test procedure](issue-automation.md#credentialed-github-smoke-test)
after human review and setup. Local tests cannot establish GitHub permissions,
repository settings, runner provisioning, or paid API access.
