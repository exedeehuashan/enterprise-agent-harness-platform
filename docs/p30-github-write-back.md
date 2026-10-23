# P30: GitHub write-back

Status: 方案 4. QA results appear where developers already look: on the pull
request. Write-back is off by default and needs a dedicated write token.

## Architecture Explanation

- **What is written.** For each same-repository pull request whose head commit was
  tested:
  - a **commit status per suite**: `eahp/<suite>`, success or failure, with a
    description such as `Failed (TESTS_FAILED): 42 tests, 2 failed`;
  - a **release-gate status**: `eahp/release-gate`, success, failure or pending,
    with the first gate reason. It is computed when sent, so it reflects all
    evidence ingested so far.
  - **one EAHP comment per pull request** carrying the commit QA report (P22),
    marked `<!-- eahp-qa-report -->`. It is updated in place (`PATCH`) on every
    new result. If someone deletes it on GitHub, a new one is posted.

  Fork pull requests are never run (P11), so nothing is written to them. The
  report's Markdown is already escaped (P22), so untrusted PR titles and test
  output cannot inject mentions or links into the comment.
- **Trigger.** Ingestion of a QA run (`QaResultIngestor`) calls
  `GitHubWriteBack.onIngested`. When the project has write-back on and a token is
  configured, it queues entries in `qa_github_outbox` (V25):
  - one `STATUS` with its payload;
  - one `GATE` and one `COMMENT` per pull request, de-duplicated while pending,
    because both are computed when sent.
- **Delivery.** `GitHubWriteBackJob` sends due entries every 15 s. Failures are
  retried with backoff (30 s, doubling, up to 1 h); after 10 attempts the entry is
  marked `FAILED`. GitHub outages never affect runs or evidence.
- **Configuration**:

  | Variable | Purpose |
  | --- | --- |
  | `EAHP_GITHUB_WRITE_TOKEN` | Token used for write-back. It needs "Commit statuses: write" and "Pull requests: write" on the tested repositories. It is separate from `EAHP_GITHUB_TOKEN`, the read-only polling token. |
  | `EAHP_PUBLIC_URL` | Optional. Commit statuses link to the console's Quality page. |

  Per project, approvers switch write-back on with
  `PUT /api/v1/qa/admin/projects/{key}/github-write-back {"enabled": true}`. The
  change is audited as `GITHUB_WRITE_BACK_*` and only GitHub projects can enable
  it.
- **Visibility.** `GET /api/v1/qa/projects/{key}/github-write-back` (VIEWER) returns
  the settings, whether a token is configured, and the latest outbox entries with
  their errors. The Test projects page shows this per project, with an approver
  toggle.

## Trade-off Analysis

Statuses rather than check runs: commit statuses work with a plain fine-grained
token, whereas check runs require a GitHub App. An App can replace the token later
without changing the outbox.

A single updated comment rather than one per run avoids flooding the PR
conversation. The comment always shows the latest state of the head commit.

## Production Considerations

- Grant the write token only the two permissions above, and only on the tested
  repositories.
- To make the gate status required for merging, add `eahp/release-gate` as a
  required status check in the repository's branch protection.
- V25 adds `qa_github_write_back`, `qa_github_outbox` and `qa_github_comments`.

## Bilingual Interview Q&A

Q: Why does write-back go through an outbox instead of calling GitHub directly?

中文：结果入库后只写一条待发送记录，由后台任务发送并带退避重试；GitHub 故障或限流不会影响测试运行和证据，失败也能在控制台看到。

English: Ingestion only records an outbox entry, and a background job sends it
with backoff retries. A GitHub outage or rate limit never affects runs or
evidence, and failures are visible in the console.

Q: Why a separate token for writing?

中文：轮询只需要只读权限；写权限单独配置、按项目开启，最小权限原则，泄露或误用的影响也更小。

English: Polling needs only read access. Write access is configured separately
and enabled per project, following least privilege, which limits the damage if
either token leaks.

## Verification

`GitHubWriteBackApiTest` runs against a fake GitHub API:

- nothing is queued until an approver enables write-back; operators get 403, and
  the settings show that a token is configured;
- with write-back on, two results queue two statuses, one gate status and one
  comment for the same-repository PR (none for the fork PR);
- the requests carry the write token and the expected paths, states, contexts,
  descriptions and target URL; the comment carries the marker and the report;
- the next result updates the comment with `PATCH` instead of posting a new one;
- a 502 schedules a retry, shows the error and is not retried early; the entries
  are sent once GitHub recovers;
- turning write-back off stops queueing.

Also: `QaSuiteToolApiTest` (30 tests) and frontend tests (22) pass.

## Observed local results

I deployed with `scripts/deploy.sh` (V25). The endpoint reports online-shop with
write-back off and `tokenConfigured: false`. No write token is configured locally,
so nothing is sent to GitHub until one is added to `.env` and an approver turns
write-back on.
