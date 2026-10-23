# P21: Test quarantine in the release gate

Status: 方案 4. A known flaky or broken test no longer has to hold every release
hostage, and it cannot quietly disappear either. An approver accepts the failure
explicitly, with a reason, for a bounded time. The gate still refuses whenever the
rest of the suite might not have run.

## Architecture Explanation

- **`qa_quarantines`** (V19) stores:
  - project, suite, test class and name;
  - the reason (at least 10 characters);
  - who created it and when;
  - `expires_at` (1 to 30 days);
  - `released_at` and `released_by`.

  A partial unique index allows one open quarantine per test. A quarantine is
  active while it is unreleased and unexpired. An expired one is closed
  (`released_by = 'expiry'`) when the test is quarantined again.
- **API**:
  - `GET /api/v1/qa/quarantines?project=&all=` (VIEWER);
  - `POST /api/v1/qa/admin/quarantines` with
    `{project, suite, testClass, testName, reason, days}`;
  - `POST /api/v1/qa/admin/quarantines/{id}/release`.

  Both admin endpoints are APPROVER-only and audited in `qa_admin_events` as
  `QUARANTINE_CREATED` and `QUARANTINE_RELEASED`.
- **Gate** (`QualityService`): when a required suite's latest run at the commit
  ended `TESTS_FAILED`, the gate checks whether every failed test is currently
  quarantined.
  - If so, and the run executed **at least as many tests as the suite's last
    passing run with the same scope** (same `modules`, P19), the check is `PASSED`
    with a note naming the quarantined tests. The outcome stays `TESTS_FAILED`, so
    the evidence is not rewritten.
  - Otherwise the gate fails and explains why. Either there is no passing baseline,
    or the run executed fewer tests, so tests after the failure may not have run.
  - Build failures, timeouts and Docker errors are never waived.
- **Tests still run.** Results, history and triage are unchanged; quarantine affects
  only the gate's verdict.
- **Console**: the Quality page has a Quarantined tests section listing the reason,
  author and expiry. Approvers can quarantine a currently failing test or release a
  quarantine early. Test labels show Vitest file paths as they are and Java classes
  by their simple name.

## Trade-off Analysis

Excluding quarantined tests from the run (for example `-Dtest=!Class#method`) would
avoid Maven's fail-fast problem, but it is runner-specific and hides the test's
ongoing results. Keeping the test in the run preserves its history, so a fixed
test is visible as passing again. The completeness check then covers the case
where Maven stops dependent modules after the failure.

The baseline comparison is deliberately strict. A PR that deletes tests and also
fails a quarantined test is not waived, which is the safe direction.

## Production Considerations

- Suites that rely on quarantine should prefer runners that run everything, such as
  Vitest, or Maven with `-Dmaven.test.failure.ignore=true`. That way a quarantined
  failure does not cut the run short and the completeness check passes.
- Quarantines are scoped to project and suite; the same test in another suite is
  not affected.
- V19 adds `qa_quarantines`.

## Bilingual Interview Q&A

Q: How do you stop quarantine from becoming a place where tests go to die?

中文：隔离必须由审批人写明原因，最长 30 天自动过期，所有创建和解除都有审计；测试仍然执行并记录结果，修好后马上能看到它恢复通过。

English: An approver must give a reason, every quarantine expires within 30 days,
creating and releasing are audited, and the test keeps running and recording
results, so a fix is visible immediately.

Q: Why compare the test count with the last passing run?

中文：Maven 在一个模块测试失败后会跳过依赖它的模块，如果只看"失败的都被隔离了"，可能把根本没跑的测试也放过去。测试数不少于上次通过的运行，才能说明其余测试确实执行了。

English: Maven skips dependent modules after a failure. "All failures are
quarantined" alone could wave through tests that never ran. Requiring at least the
test count of the last passing run shows the rest actually executed.

## Verification

- `QaSuiteToolApiTest` (26 tests) adds a quarantine case covering:
  - the gate fails before quarantine;
  - operator 403, and invalid days, reason or fields 400; an unknown project 404;
    a duplicate 409;
  - with the quarantine, the gate passes with the note and the outcome kept;
  - a run with fewer tests than the passing baseline still fails, with the
    explanation;
  - viewers list active and all quarantines;
  - release is approver-only, the gate fails again, and both actions are audited;
  - an expired quarantine stops applying and is closed by `expiry` when the test is
    quarantined again.
- Frontend: 21 tests, including quarantining a failing Vitest test with a reason
  and releasing it.

## Observed local results

Deployed with `scripts/deploy.sh` (V19 applied). Live checks:

- `GET /api/v1/qa/quarantines` returns 200 for online-shop (none yet);
- an operator creating a quarantine gets 403, and an unauthenticated read 401;
- online-shop `main` stays PASS on `backend-unit` and `frontend-unit`.
