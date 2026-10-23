# P25: Exit-code suites and more of online-shop's test types

Status: 方案 4. EAHP can now run checks that write no JUnit reports. online-shop's
contract checks and Playwright end-to-end tests now run through EAHP alongside its
unit, integration, frontend unit and API tests.

## Architecture Explanation

- **`"results": "exit-code"`** for repository-declared suites, with an empty
  `reportDirs`. The suite's exit code becomes one test case named after the suite:
  - exit 0 records it as passed;
  - a non-zero exit records it as failed with `exit code N`, using the last 4,000
    characters of the console as the failure detail;
  - stopped and timed-out runs, and runner errors (exit 125 to 127), keep their own
    outcomes rather than being recorded as test failures.

  Because the result is ordinary JUnit, ingestion, triage, history, gates, reports
  and comparisons apply unchanged.
- `"results": "junit"` (the default) now requires at least one report directory,
  so a suite that can never produce results is rejected when the file is parsed.
- **online-shop suites added in its repository file**:
  - `contracts`: `node scripts/check-foundation.mjs && node tests/contracts/verify.mjs`
    in `node:22.21.1-bookworm-slim`, with no network and exit-code results;
  - `frontend-e2e`: Playwright in `mcr.microsoft.com/playwright:v1.56.1-noble` with
    the JUnit reporter (`frontend/order-web/test-results`). The specs mock the API,
    so the suite is self-contained. Selection is by `paths` on `frontend/order-web/`
    (P20).
- **Policy**: online-shop's allowed images now include the Playwright image, an
  audited admin change.

## Trade-off Analysis

One synthetic test case per exit-code suite is coarse: history shows "the contract
check failed", not which contract. The console tail in the failure detail and the
stored console log carry the specifics. A project that wants per-check history can
make its script emit JUnit.

Performance tests (k6) are left for environment management: they need a running
stack, and online-shop treats their thresholds as observational only.

## Production Considerations

- The Playwright image is large (about 2 GB); pull it once on the runner host.
- `frontend-e2e` installs npm packages on each run; the per-project npm cache keeps
  this fast after the first run.
- No migration.

## Bilingual Interview Q&A

Q: How do you bring a check without test reports into a test platform?

中文：把它的退出码当作一个测试用例：0 为通过，非 0 为失败并附上日志尾部；这样历史、门禁和报告都能统一处理，而停止、超时和运行器错误仍保留各自的结论。

English: Treat its exit code as one test case: 0 passes, non-zero fails with the
console tail attached. History, gates and reports then handle it like any other
suite, while stops, timeouts and runner errors keep their own outcomes.

## Verification

`QaSuiteToolApiTest` (29 tests) adds an exit-code case:

- exit 0 records 1 passing test, and a JUnit file left in the workspace is ignored;
- exit 3 gives `TESTS_FAILED` with `exit code 3` and an ingested failing case named
  `smoke`;
- exit 127 gives `RUNNER_ERROR`;
- an exit-code suite with `reportDirs`, and a JUnit suite without any, are both
  rejected.

## Observed local results

I deployed with `scripts/deploy.sh` and ran both new suites at the online-shop
branch that declares them (#55):

| Suite | Result |
| --- | --- |
| `contracts` | Passed in under a second, recorded as 1 test |
| `frontend-e2e` | Passed 5 Playwright tests in 16 s inside the Playwright image (JUnit parsed) |

With these, online-shop's test types in EAHP are:

- backend unit tests;
- backend integration tests (Docker, approval-gated);
- frontend unit tests;
- frontend end-to-end tests;
- contract checks;
- API tests against the UAT environment (P24).
