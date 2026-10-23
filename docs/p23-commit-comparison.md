# P23: Commit comparison

Status: 方案 4. For a pull request (or any two commits), EAHP shows how the test
results changed, not only whether they pass:

- what the change broke;
- what it fixed;
- what it added or removed.

## Architecture Explanation

- `QaComparison.compare(project, base, head, suite?)` takes each suite's latest
  ingested run at both commits and compares per-test statuses
  (`class#method`; a test reported twice counts as failing if any report failed):
  - **new failures**: failing at head and passing at base, plus new tests that fail
    (nothing shows they ever passed);
  - **fixed**: failing at base and passing at head;
  - **still failing**;
  - **added**;
  - **removed**: only when both runs had the same scope (full, or the same modules,
    P19), because a selective run naturally executes fewer tests.

  Each diff reports both sides' run, outcome, test count and scope, with counts and
  up to 50 names per list. A suite without a result at the base is
  `NO_BASE_RESULT`.
- API: `GET /api/v1/qa/projects/{key}/compare?base=&head=&suite=` (VIEWER). Branch
  and tag names are resolved to commits.
- **QA report (P22)**: for a pull request head, a "Compared with base" section
  lists each suite's counts and its new failures. Base results usually exist,
  because the ON_CHANGE schedule (P17) tests every new `main` commit.
- **Console**: the Quality page has a Compare commits form (base defaults to the
  default branch) showing counts and the new-failure, fixed and removed lists.

## Trade-off Analysis

Comparing against the base commit's own results, instead of history across
branches, answers the reviewer's question directly: did this change make things
worse? Flakiness and long-running failures are still covered by the triage history
labels (P13).

Comparing only the latest run on each side keeps the comparison deterministic. A
re-run at the head replaces its side; nothing is averaged.

## Production Considerations

- When the base has no result (for example `main` moved without a schedule), the
  comparison says so rather than guessing from an older commit.
- No migration; it reads `qa_run_ingestions` and `qa_test_results`, so retention
  (P18) bounds how far back comparisons work (180 days by default).

## Bilingual Interview Q&A

Q: Why not report removed tests when the scopes differ?

中文：按模块选择的运行本来就只跑部分测试，如果和全量运行比较，所有没跑的测试都会被误报成"被删除"。只有范围相同时，少掉的测试才说明真的被删了或没执行。

English: A module-selective run executes only part of the suite. Compared with a
full run, every test it skipped would look removed. Only with the same scope does
a missing test mean it was deleted or did not run.

Q: Why does a new test that fails count as a new failure?

中文：它从来没有通过过，合并后就是一个新的失败；把它算进"新增"会让审查者忽略它。

English: It never passed, so merging it adds a failure. Counting it only as
"added" would hide it from the reviewer.

## Verification

- `QaSuiteToolApiTest` (28 tests) adds a comparison case covering:
  - a new failure from base to head;
  - a fix, an added test and a removed test with the same scope;
  - no base result;
  - an invalid suite (400) and an unauthenticated request (401).

  The report case now checks the "Compared with base" section.
- Frontend: 21 tests, including the comparison view.
