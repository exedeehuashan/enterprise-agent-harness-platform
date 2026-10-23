# P15: Release gate and quality dashboard

Status: the 方案 4 release gate and the quality view a QA lead uses before a
release. Both are computed only from recorded evidence (ingested runs and per-test
results from P13, impact from P14); neither runs anything.

## Architecture Explanation

- **Gate policy** (`qa_quality_gates`): the suites that must pass for a project.
  online-shop requires `backend-unit`.
- **Gate verdict** for a commit (`GET /api/v1/qa/projects/{key}/gate?ref=`; a branch
  or tag is resolved to its current commit):
  - `PASS`: the latest ingested run of every required suite at that commit passed;
  - `FAIL`: a required suite's latest run did not pass, with the outcome and triage
    category as the reason;
  - `PENDING`: a required suite has not run at that commit, or is running again;
  - `NOT_CONFIGURED`: the project has no gate.
  Missing evidence is never a pass. Notes add context from the test history:
  new failures (with the last passing commit), flaky tests, failures that were
  already there.
- **Dashboard** (`GET /api/v1/qa/projects/{key}/dashboard?days=`, 1–90):
  - runs, passes and pass rate overall and per suite, with a daily trend;
  - failing now: tests that failed in each suite's latest run, with how many runs
    they have been failing and since which commit (a test that failed earlier but
    no longer runs in the suite is not listed);
  - flaky tests (passed and failed on the same commit), slowest passing tests;
  - recent pull requests with their gate verdict and cached impact summary.
- **Console Quality page**: stat tiles, a "Check gate" form, per-suite daily pass
  rate as thin bars on a 0–100% scale (one series, one hue, hover tooltips and a
  table view), failing/flaky/slowest tests and the PR gate table.

## Trade-off Analysis

The gate checks suites, not individual tests. Any failing test already fails its
suite, so separate "block on new failure" or "block on flaky" switches would
change nothing; those facts are reported as notes instead. A gate that tolerates
known failures (quarantine) would need an explicit, audited exception list, which
is deliberately not part of this phase.

Computing the dashboard at request time keeps it exact and avoids another
background job; the queries are bounded (latest runs, top lists, a time window).

## Production Considerations

- The gate reflects what has run. Pair it with the PR trigger (P11) or a scheduled
  run on the release branch so required suites actually run at release commits.
- Gate policies are edited in the database for now; there is no API to change them.
- V14 adds `qa_quality_gates` and indexes for gate and dashboard queries.

## Bilingual Interview Q&A

Q: Why is a missing test result "pending" instead of "pass"?

中文：没有证据就不能证明质量。门禁只根据已记录的结果判断，缺少结果时提示需要运行，而不是默认通过。

English: No evidence proves nothing. The gate judges only recorded results; when a
result is missing it asks for a run instead of assuming success.

Q: What should "failing now" mean on a dashboard?

中文：应该看每个套件最近一次运行里失败的测试。如果只看每个测试最后一次的结果，被移出套件的旧失败会一直显示，误导判断。

English: Tests that failed in each suite's most recent run. Looking only at each
test's last result keeps showing stale failures of tests that no longer run in the
suite.

## Verification

- `QaSuiteToolApiTest` (20 tests) adds: gate PENDING before any run, PASS after a
  passing run, FAIL with reason and a new-failure note, PENDING while running,
  NOT_CONFIGURED, 400/404/401; dashboard counts, pass rate, daily trend, failing
  now with streak and cause, flaky and slow tests, and a test leaving the suite no
  longer counted as failing.
- Frontend: 18 tests, including the Quality page and gate check. Related backend
  suites: 98 pass.
- Also in this branch: two fixes to P14's change impact from code review (root
  modules of single-module projects, parent POMs in subdirectories), with
  regression tests.

## Observed local results

On the local deployment: gate for online-shop `main` (`1ca9ae5`) is **FAIL**
(`backend-unit failed (TESTS_FAILED, ENVIRONMENT_DOCKER)`, note: CatalogMigrationTest
already failing); `39a6a0b` is **PASS**. Dashboard: 8 runs, 50% pass rate; failing
now: CatalogMigrationTest for 3 consecutive runs; PR gates #50 FAIL, #49 PASS,
#47 FAIL. The first version listed IdentityPersistenceTest as failing although it
no longer runs in the suite; "failing now" was redefined as above.

A browser check on a throwaway backend (fresh tokens, two real online-shop runs)
found and fixed: failure outcomes shown as grey badges, a trend bar without a scale
reference, and long test names overflowing the page horizontally. After the fixes
the page had no horizontal scroll at desktop or phone width.
