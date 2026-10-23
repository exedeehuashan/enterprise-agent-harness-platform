# P19: Impact-based test selection

Status: 方案 4. Pull request runs use the change impact from P14 to build and test
only what a change can affect. When the impact is uncertain, they still run
everything.

## Architecture Explanation

- **Opt-in per suite.** A repository-declared suite may set
  `"selection": "maven-modules"` (P12 file format). The parser accepts no other
  value and requires an `mvn` command. Registry suites do not support selection.
- **The decision** comes from `ImpactSelection.decide(impact, suitesFile)`:
  - `MODULES`: the affected modules (changed modules plus modules that depend on
    them), at most 50.
  - `NOT_AFFECTED`: no module changed (for example a frontend- or docs-only PR).
    The PR run is skipped with `NOT_AFFECTED`.
  - `FULL`, with one of these reasons:

    | Reason | When |
    | --- | --- |
    | `IMPACT_UNAVAILABLE` | Impact unavailable or base unknown |
    | `NOT_MAVEN` | Not a Maven build |
    | `TOO_MANY_CHANGES` | Too many changes (truncated) |
    | `BUILD_CHANGED` | The root `pom.xml`, any other `pom.xml` outside a module, `.mvn/`, `mvnw`, or the `.eahp/` directory (including the suites file) changed |
    | `ROOT_MODULE` | The root module itself is affected |
    | `TOO_MANY_MODULES` | More than 50 modules are affected |

    Suites without selection always run in full (`SUITE_NOT_SELECTIVE`).
- **Execution.** `PullRequestTriggers` records `selection`, `selection_reason` and
  `selected_modules` on `qa_pr_runs` (V18) and passes `modules` to the tool. The QA
  tools accept an optional `modules` argument:
  - each entry must be a relative module directory with a `pom.xml` at the run's
    commit;
  - entries are de-duplicated and sorted;
  - the list is stored in the normalized arguments, so approvals bind it;
  - the executor appends `-pl <modules> -am`.
- **Evidence.** `summary.json` records `scope` (`FULL` or `MODULES`) and `modules`.
  The release gate's suite checks carry `modules`, and the gate adds a note when a
  suite ran only some modules.
- **Console.** The Pull requests page shows "N affected modules only", "full
  suite: <reason>", or "No backend module affected: not needed".

## Trade-off Analysis

`-am` also runs the tests of the modules the selection depends on. That makes the
run a superset of what the change can break through compile-time dependencies, at
the cost of some extra time. Narrowing further (`-Dtest=` per class) would rely on
naming conventions and miss tests that cover a change only indirectly.

Skipping a suite when no module is affected is the main saving for frontend-only
pull requests. The analysis can be wrong in two places, and both fall back to the
full suite:

- build configuration outside the modules;
- changes too large to list completely.

Selection applies only to pull request runs. Schedules on `main` (P17) keep running
the full suite, so the gate for the branch that ships is always based on full
evidence.

## Production Considerations

- A gate on a commit that only a selective run covered passes with a note. It does
  not report `PENDING`. For release branches, rely on the full ON_CHANGE run.
- Modules are module directories (as in the impact), not artifact IDs; Maven
  accepts both for `-pl`.
- V18 adds the selection columns to `qa_pr_runs`.

## Bilingual Interview Q&A

Q: How do you make test selection safe?

中文：只在确定的时候缩小范围。影响分析不完整、构建文件改动、根模块受影响或改动过多时，一律跑全量；`-am` 同时跑依赖模块的测试；主分支的定时运行始终是全量。

English: Narrow only when certain. Incomplete impact, build-file changes, the root
module or very large changes all run the full suite; `-am` also tests dependencies;
and scheduled runs on main are always full.

Q: Why is selection opt-in in the repository file?

中文：只有项目自己知道这个套件是不是按模块组织的 Maven 构建。把它写在仓库里，选择策略和代码一起评审、一起演进。

English: Only the project knows whether a suite is a module-structured Maven build.
Declaring it in the repository means the selection policy is reviewed and evolves
with the code.

## Verification

- `ImpactSelectionTest` (3 tests): module selection, the not-affected skip and
  every fallback.
- `PullRequestTriggerApiTest` (12 tests) adds two cases:
  - a selective suite runs `[api, core]` for a core change, skips a frontend-only
    PR as `NOT_AFFECTED`, and runs in full when `.mvn/` changes;
  - non-selective suites run in full without computing impact.
- `QaSuiteToolApiTest` (25 tests) adds a manual selective run:
  - modules are de-duplicated and sorted;
  - the run ends with `-pl backend/catalog-api,backend/order-api -am`;
  - the summary has scope `MODULES`, and the gate passes with a note;
  - invalid, absolute, traversal, missing and unsupported module lists are
    rejected, and so are invalid `selection` values.
- Frontend: 20 tests, including the selection labels.

## Observed local results

Deployed with `scripts/deploy.sh` (V18 applied). On the online-shop branch that
enables selection (#52), a manual `backend-unit` run with
`modules=["backend/catalog-api"]`:

- built only the catalog chain: catalog-domain → catalog-application →
  catalog-adapters-persistence → catalog-api;
- ran 3 tests and passed in 7 s, compared with 42 tests and about 15 s for the full
  suite;
- recorded scope `MODULES` in its summary.

The GitHub poller then picked up #52 itself. It recorded `FULL / BUILD_CHANGED`,
because the PR changes `.eahp/qa-suites.json`, and the full suite completed.
