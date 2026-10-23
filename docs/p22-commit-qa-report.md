# P22: Commit QA report

Status: 方案 4. For any commit, one readable summary of what was tested and what
the evidence says. It can be pasted into a review, a release ticket or (once
write-back is enabled) a pull request comment.

## Architecture Explanation

- `GET /api/v1/qa/projects/{key}/report?ref=` (VIEWER) resolves the ref to a commit
  and returns `{project, ref, commit, verdict, generatedAt, markdown}`.
- `QaReportService` builds the Markdown from recorded evidence only:
  - the release gate verdict and required suites (P15), with its reasons and notes,
    including selection (P19) and quarantine (P21) notes;
  - a table of every suite's latest ingested run at the commit: result, tests,
    failures, scope (full or N modules) and run ID;
  - failures with status, triage category and history label (P13), at most 10 per
    suite;
  - when the commit is a pull request head: the PR number and title, how each suite
    was selected or why it was skipped, and the cached change impact (P14).
- **Untrusted text is inert.** Test names, failure messages, PR titles and notes are
  collapsed to one line and bounded to 300 characters. HTML, emphasis, link, table
  and heading syntax is escaped, and `@` mentions are broken with a zero-width
  space. Test names sit in code spans with backticks replaced. A hostile PR title
  therefore cannot add links, ping people or inject markup into a posted comment.
- **Console.** After a gate check on the Quality page, "QA report" shows the
  Markdown and "Copy Markdown" copies it.

## Trade-off Analysis

Markdown keeps the report portable: GitHub, chat tools and tickets all render it.
Returning it inside JSON, rather than as `text/markdown`, keeps the client's single
JSON code path and adds the verdict and commit as structured fields for
automation.

The report reads cached impact only and never fetches from git (apart from
resolving a branch name), so generating it is cheap and safe to call often.

## Production Considerations

- The report is a snapshot (`generatedAt`). Regenerate it after new runs.
- Run IDs are shortened to 8 characters for readability; the console links runs
  by full ID.
- No migration.

## Bilingual Interview Q&A

Q: Why escape the report so aggressively?

中文：报告里的 PR 标题、测试名和失败信息都来自不可信的代码和提交者。将来自动发到 PR 评论时，如果不转义，别人可以借此插入链接、@ 提及或伪造内容。

English: PR titles, test names and failure messages come from untrusted code and
contributors. Once the report is posted automatically, unescaped text could inject
links, @-mentions or forged content.

Q: Why build the report from recorded evidence only?

中文：报告要和门禁结论一致、可复现；它只读取已记录的运行和缓存的影响分析，不会临时重新计算出不同的结果。

English: The report must match the gate and be reproducible. It reads recorded
runs and cached impact only, so it never recomputes a different answer on the fly.

## Verification

- `QaReportTextTest` covers escaping of HTML, links, mentions, headings, tables and
  emphasis, plus bounding and code spans.
- `QaSuiteToolApiTest` (27 tests) adds a report case covering:
  - the FAIL verdict, the suites table row and gate details;
  - the failure line with its triage and an escaped message;
  - a PR section with a hostile title rendered inert and the module selection;
  - a commit without results (PENDING);
  - a bad ref 400, an unknown project 404 and unauthenticated 401.
- Frontend: 21 tests, including showing and copying the report.

## Observed local results

Deployed with `scripts/deploy.sh`.

- **online-shop `main` (`b161fff`)**: the report shows **PASS**, with
  `backend-unit` (42 tests) and `frontend-unit` (46 tests), both full.
- **The head of online-shop#52**: the report shows **PENDING**, because
  `frontend-unit` became required after that commit. It also shows the PR title
  with its parentheses escaped and `backend-unit: full suite (build changed)`. The
  change impact is one file outside the Maven modules.
