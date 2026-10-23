# P20: Path-based selection and a frontend suite

Status: 方案 4 now covers online-shop's frontend as well as its backend. A suite can
declare which paths it tests, so pull requests run only the suites their changes
touch, whatever the build system.

## Architecture Explanation

- **`"selection": "paths"`** with a `paths` list (1 to 20 relative directories or
  files, for example `"frontend/order-web/"`). It is declared in the repository
  suites file and parsed strictly:
  - `paths` is required with this selection and rejected without it;
  - absolute paths and paths containing `..` are invalid.
  - The paths are bound into the run's suite definition.
- **Decision** (`ImpactSelection.decidePaths`). It uses only the PR's changed file
  list from the change impact (P14), so it works for npm, Gradle or anything else:
  - **Runs in full**:
    - a changed file is under one of the paths (`PATHS_CHANGED`), with directory
      matching on `/` boundaries, so `frontend/order-webx/` does not match;
    - the suites file or `.eahp/` changed (`BUILD_CHANGED`);
    - the change list is incomplete (`TOO_MANY_CHANGES`);
    - the impact is unavailable.
  - **Skipped** as `NOT_AFFECTED` / `NO_PATH_CHANGED` otherwise.
- **Combined with P19**: on a frontend-only PR, `backend-unit` (`maven-modules`) is
  skipped and `frontend-unit` runs; on a backend-only PR it is the other way round.
- **online-shop `frontend-unit`**:
  - runs in `node:22.21.1-bookworm-slim`:
    `npm ci && npx vitest run --reporter=junit --outputFile.junit=test-results/TEST-vitest.xml`;
  - its reports go to `frontend/order-web/test-results`, so ingestion, triage,
    history and the gate work unchanged;
  - the npm cache lives in the per-project runner cache.
- **Policy and automation**, set through the audited admin API (P16/P17):
  - the release gate now requires `backend-unit` and `frontend-unit`;
  - online-shop's allowed images now include the Node image;
  - a PR trigger for `frontend-unit`;
  - an ON_CHANGE schedule for `frontend-unit` on `main`.

## Trade-off Analysis

Paths are coarse, but they are explicit and reviewed with the code. Dependency
graphs for npm workspaces would be finer, but online-shop's frontend is a single
package, so a path prefix already gives the useful answer: did the app change?

Lint, typecheck, build and Playwright UAT stay in online-shop's GitHub Actions.
EAHP runs the test suites whose per-test evidence feeds history and the gate.

## Production Considerations

- The first run downloads npm packages (about 3 minutes). Later runs reuse the
  per-project npm cache.
- Pin images by exact tag, as done here; the policy allowlist matches exact
  strings.

## Bilingual Interview Q&A

Q: How does EAHP decide which suites a pull request needs?

中文：每个套件在仓库里声明自己的选择方式：Maven 套件按受影响模块缩小范围，前端套件按路径判断是否需要运行；任何不确定的情况都跑全量。

English: Each suite declares its selection in the repository. Maven suites narrow
to the affected modules, and path suites run only when their files change. Anything
uncertain runs in full.

Q: Why not let the frontend suite always run?

中文：后端改动不会影响前端单元测试，跳过它可以节省几分钟的运行时间和资源；而改动套件定义本身时仍然会全量运行。

English: Backend changes cannot break the frontend unit tests, so skipping saves
minutes of runner time. A change to the suite definitions still runs everything.

## Verification

- `ImpactSelectionTest` (4 tests) adds path matching:
  - prefix boundaries;
  - an exact file path;
  - suites-file changes;
  - incomplete change lists;
  - missing impact.
- `PullRequestTriggerApiTest` (13 tests) adds a paths suite, which is skipped for
  a backend change and runs for a `web/` change with its paths bound.
- `QaSuiteToolApiTest` (25 tests) adds validation of `paths` and `selection`.
- Frontend: 20 tests.

## Observed local results

- Deployed with `scripts/deploy.sh`.
- On the online-shop branch that declares the suite (#53), a manual `frontend-unit`
  run passed: exit 0, 46 Vitest tests, JUnit parsed, 191 s with a cold npm cache.
- The GitHub poller then started both `backend-unit` and `frontend-unit` for #53
  as `FULL / BUILD_CHANGED`, because the PR changes the suites file.
- After #53 was merged (`b161fff`), both ON_CHANGE schedules on `main` ran their
  suites in full: `backend-unit` passed, and `frontend-unit` passed 46 tests in
  13 s with a warm npm cache.
- online-shop's release gate now requires `backend-unit` and `frontend-unit`, and
  `main` is **PASS** on both.
