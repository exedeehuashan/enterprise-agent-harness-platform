# P29: Code coverage

Status: 方案 4. QA runs record line and branch coverage. The release gate can
require a minimum line coverage per suite, and QA reports show coverage next to
test results.

## Architecture Explanation

- **Declaration.** A repository suite lists coverage reports as path suffixes, for
  example `"coverage": ["target/site/jacoco/jacoco.xml"]`. Paths must be relative
  `.xml` paths, and the parser rejects anything else.
- **No project changes needed.** For Maven projects, coverage can be collected
  without touching the project's poms. online-shop's `backend-unit` command is:

  ```
  mvn … org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.12:report
  ```
- **Parsing** (`CoverageReports`):
  - finds matching files in the workspace: up to 200 files, skipping `node_modules`;
  - streams each report (50 MiB maximum) with StAX, with DTDs and external entities
    disabled, so the DOCTYPE in JaCoCo reports is never loaded and XXE is
    impossible;
  - reads only report-level totals: for JaCoCo, the `LINE` and `BRANCH` counters
    that are direct children of `<report>` (named after the module); for
    Cobertura, the `lines-*` and `branches-*` attributes on `<coverage>`.
  - Unreadable files are counted, not fatal.
- **Evidence.** A `coverage.json` artifact holds the per-module totals.
- **Ingestion.** Totals are stored in `qa_coverage` (V24), one row per run. A run
  without any readable report records nothing.
- **Gate.** `qa_quality_gates.min_line_coverage` maps required suites to minimum
  percentages, set with `PUT …/gate {"requiredSuites": […], "minLineCoverage": {"backend-unit": 70}}`.
  For a passing full-scope run:
  - coverage below the minimum fails the gate (`… line coverage 61.2% is below the gate's 70.0%`);
  - a missing coverage report fails it as well.

  Module-selected runs (P19) cover only part of the code, so the check is skipped
  with a note. Gate suite checks carry `lineCoverage`.
- **Report.** The commit QA report (P22) has a Line coverage column.
- **Console.** The release gate form has a "Minimum line coverage" field
  (`backend-unit=70, …`).

## Trade-off Analysis

Reading report-level totals rather than per-class data keeps ingestion small and
robust. Per-file detail stays in the project's own HTML report if they produce
one. A coverage diff on changed lines would need per-line data; it can build on
this later.

Thresholds are per suite: a unit suite and an integration suite cover different
code, so a single project-wide number would mislead.

## Production Considerations

- Coverage needs the suite to produce a report. The JaCoCo plugin is fetched from
  Maven Central on the first run (network egress) and cached afterwards.
- Unit-test coverage alone understates the real coverage of adapter and API
  modules, which online-shop tests in its integration suite. Add `coverage` there
  too if you gate on it.
- V24 adds `qa_coverage` and `qa_quality_gates.min_line_coverage`.

## Bilingual Interview Q&A

Q: How do you parse coverage reports from untrusted builds safely?

中文：用流式 StAX 解析，关闭 DTD 和外部实体，所以 JaCoCo 报告里的 DOCTYPE 不会被加载，XXE 无法读取本地文件；只读顶层汇总数，并限制文件大小和数量。

English: Streaming StAX with DTDs and external entities disabled. The DOCTYPE in
JaCoCo reports is never loaded and XXE cannot read local files. Only top-level
totals are read, and file size and count are bounded.

Q: Why skip the coverage threshold for module-selected runs?

中文：按模块选择的运行只覆盖部分代码，覆盖率百分比和全量运行不可比，强行比较会误判，所以只在全量运行上执行阈值，并在门禁里注明。

English: A module-selected run covers part of the code, so its percentage isn't
comparable with a full run. The threshold applies to full runs only, and the gate
notes the skip.

## Verification

- `CoverageReportsTest`:
  - JaCoCo totals are taken from report-level counters only, with the DOCTYPE
    present;
  - Cobertura totals are read from the root attributes;
  - an XXE attempt never exposes the file;
  - reports are found by suffix (`node_modules` excluded) and summed across
    modules;
  - a broken file is counted as unreadable.
- `QaSuiteToolApiTest` (30 tests) adds a coverage case:
  - `coverage.json` evidence and the ingested totals;
  - the gate fails below the minimum (with the reason and `lineCoverage`) and
    passes once it is met;
  - the report shows `75.0%`;
  - a run without a report fails the coverage gate;
  - invalid minimums and coverage paths are rejected.
- `QaAdminApiTest` (3 tests) is unchanged.
- Frontend: 22 tests.

## Observed local results

Deployed with `scripts/deploy.sh` (V24). On the online-shop branch that adds
JaCoCo to `backend-unit` (#56), the suite passed 42 tests in 21 s and recorded:

- **lines:** 277 of 658 covered (42.1%);
- **branches:** 132 of 314 covered (42.0%);
- **per module:** for example `order-domain` 145/169 lines, `order-application`
  113/143, and `order-api` 0/82, which is covered by the integration suite instead.
