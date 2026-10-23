# P31: Scheduled API tests

Status: 方案 4. API test collections (P24) run on the same schedules as suites
(P17): when a branch changes, or on a UTC timetable such as a nightly smoke test of
the UAT environment.

## Architecture Explanation

- **Schedules (V26).** `qa_schedules` gains an optional `environment` column. A
  schedule with an environment runs an API test collection, named in `suite_key`,
  against that environment. Uniqueness now covers (project, suite, ref, kind,
  environment), so the same collection can run against several environments.
- **Creation.** `POST /api/v1/qa/admin/schedules` accepts `"environment": "uat"`.
  It is validated against the project's registered environments, and the audit
  entry records it.
- **Check.** For an API schedule, the ref is resolved to a commit (`git ls-remote`),
  and EAHP starts `QA_RUN_API_TESTS` with `{project, collection, environment, ref}`.
  The collection is validated at that commit when the run starts (P24
  normalization), so a removed collection or environment is recorded as the
  schedule's error. ON_CHANGE and CRON behave exactly as for suites: one task per
  schedule, no duplicate runs for the same commit or slot, and a check is skipped
  while the previous run is still active.
- **Console.** The schedule form has "Suite or API collection" and an optional
  "Environment" field; the list shows `@ ref → environment`.
- **online-shop.** It now has CRON schedules at 02:00 UTC for `catalog-api-smoke`
  and `order-api-smoke` against `uat`.

## Trade-off Analysis

API tests exercise what is deployed, not the commit. A schedule records the ref's
commit (what the test cases come from), but the environment may run another build.
Scheduled API tests therefore suit environments that are redeployed from that ref,
such as UAT from `main`. Testing a pull request's own build needs an environment
created for that commit, which is the next step (environment management).

## Production Considerations

- API test runs create synthetic data in the target environment, so prefer nightly
  CRON schedules over ON_CHANGE on busy branches.
- V26 replaces the unique constraint on `qa_schedules` with a unique index that
  includes the environment.

## Bilingual Interview Q&A

Q: Why aren't API tests triggered on pull requests like test suites?

中文：API 测试打的是已部署的环境，而 PR 的代码并没有部署到 UAT；在 PR 上跑只会测到主干的版本，结果会误导。所以 API 测试用定时或分支变更触发，等有了按提交创建的环境再接入 PR。

English: API tests hit a deployed environment, and a PR's code is not deployed to
UAT, so running them on a PR would test main's build and mislead. They run on
schedules or branch changes until per-commit environments exist.

## Verification

- `QaApiTestApiTest` (4 tests) adds a schedule case:
  - an unknown environment is rejected;
  - an ON_CHANGE schedule starts `QA_RUN_API_TESTS` with the collection and
    environment;
  - the run executes (the fixture's failing cases give `TESTS_FAILED`);
  - the same commit does not run again.
- `QaSuiteToolApiTest` (30 tests) and the frontend tests (22) are unchanged.

## Observed local results

Deployed with `scripts/deploy.sh` (V26).

- Created nightly schedules for both online-shop collections against `uat`.
- A temporary ON_CHANGE schedule for `catalog-api-smoke` on `main` was picked up by
  the scheduler. The run passed 3 of 3 cases against the UAT stack, and the
  schedule was then deleted.
