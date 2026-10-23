# P10: QA workflows in the web console

Status: the 方案 4 test loop is usable without curl. QA engineers pick a project,
suite and ref in the console, follow the run, read the test result and download
the evidence.

## Architecture Explanation

- **Run test suite** form on the task page reads `GET /api/v1/qa/projects`. It
  defaults to an isolated suite. Choosing a suite with Docker access switches to
  `QA_RUN_PRIVILEGED_SUITE` and explains that an approver must approve the exact
  commit; the backend enforces the same split, so the UI only reflects policy.
  The generic run form no longer offers the QA tools, which need arguments.
- **Run page** shows the run's arguments and, for QA runs, a **Test result**
  panel built from `summary.json`: outcome, exit code, counts, duration, runner
  image, command, report counts and the failing tests with their messages.
- **Artifacts** section on every run lists names, sizes, attempts and SHA-256
  digests. Downloads go through `fetch` with the Authorization header and are
  saved from a local `blob:` URL, so the token never appears in a link, history
  or server log.
- **Test projects** page lists registered projects and suites with their access
  level, network mode, time limit and exact command.
- Run lists label QA runs as `project/suite @ commit`.

## Trade-off Analysis

The summary is read from the run's own `summary.json` artifact rather than a new
backend endpoint. The evidence shown is exactly the evidence stored, and the
backend stays domain-neutral; the console's QA module is the only place that
knows the summary format.

Downloads use blobs instead of direct links. That keeps tokens out of URLs at the
cost of holding a file in browser memory; artifacts are capped at 5 MiB each.

## Production Considerations

- Maven inside test containers now uses 30 s connect and 120 s request timeouts.
  The browser walkthrough hit a stalled Maven Central download that would
  otherwise have waited up to 30 minutes, silently consuming the suite deadline.
- A cancelled QA run keeps its partial evidence (reports and log up to the stop)
  and shows the outcome as STOPPED.

## Bilingual Interview Q&A

Q: Why not put API tokens in artifact download links?

中文：URL 会进入浏览器历史、代理和服务器日志，容易泄露。用带 Authorization 头的请求取回内容，再用本地 blob 链接保存，令牌不会出现在任何 URL 中。

English: URLs end up in browser history, proxies and server logs. Fetching with
an Authorization header and saving from a local blob URL keeps the token out of
every URL.

Q: Why does the console show results from summary.json instead of its own view model?

中文：页面展示的就是存档的证据本身，审阅者看到的和下载的是同一份数据；后端也不必理解 QA 的格式。

English: The page shows the stored evidence itself, so reviewers see exactly what
they can download, and the backend does not need to understand the QA format.

## Verification

- Vitest: 15 tests, including downloads using the Authorization header without the
  token in the URL; Docker-access suites using the approval-gated tool with the
  chosen ref; isolated suites by default and empty refs omitted; the test result
  panel with failing tests and artifact sizes; the projects catalog.
- Backend `QaSuiteToolApiTest` asserts the Maven network timeouts are passed.

## Observed local results

Browser walkthrough against a throwaway backend and database with freshly
generated tokens and its own QA volume (removed afterwards):

1. The Test projects page listed online-shop with its two suites.
2. An operator created a task and started `backend-unit` from the form; the run
   resolved `main` to `39a6a0b`. With a cold Maven cache it stalled on a Maven
   Central download (idle CPU and network for 10 minutes).
3. Cancel run in the UI killed the container: the run became CANCELLED, the
   outcome STOPPED, and the partial evidence (36 tests, reports, log) was kept.
4. After adding the Maven network timeouts, a new run from the form passed:
   exit 0, 42 tests, 24 s. The result panel and artifact list showed it, and
   Download saved `summary.json` from a blob URL with no token in any URL.
5. The run page had no horizontal scroll at phone width.

The console and backend were then redeployed to the local stack with
`scripts/deploy.sh` (backup taken, smoke test passed).
