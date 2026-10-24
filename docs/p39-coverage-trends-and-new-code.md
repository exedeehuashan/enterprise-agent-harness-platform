# P39: Coverage trends and new-code coverage

Status: 方案 4. P29 recorded a total coverage percentage per run, and the gate
could require a minimum for the whole suite. Two things were missing:

- a view of how coverage moves over time;
- the coverage that matters in review: **of the lines this change adds or modifies,
  how many do the tests execute?**

A project at 42% total can still require that new code is well tested.

## Architecture Explanation

- **Line data.** For suites with `coverage` reports, the runner now also reads
  per-line data (`CoverageLines`). It uses the same safeguards as P29: streaming
  parsing, no DTDs or entities, size limits, and at most 20,000 files.
  - JaCoCo `<sourcefile><line nr mi ci>` (covered when `ci > 0`): the file is named
    by package path.
  - Cobertura `<class filename><line number hits>`: lines repeated per method count
    as covered when any entry covered them.
  - Each report belongs to the module directory above its `target/`, `build/` or
    `coverage/` directory, for example `backend/order-api/`.

  The data is stored as `coverage-lines.json` evidence (if it fits the 5 MiB
  evidence limit) and ingested into `qa_coverage_lines` (V32: run, module, path,
  covered and missed line arrays).
- **Changed lines.** `GitMirror.changedLines(base, head)` runs
  `git diff -U0 --no-renames base...head`, which compares against the merge base,
  as a pull request does. It collects the added and changed line numbers per file
  at head; deletions add nothing.
- **New-code coverage** (`NewCodeCoverage.compute`):
  1. Match each changed file to a report entry by module prefix and path suffix
     (`backend/order-api/src/main/java/com/example/order/Order.java` ↔
     `backend/order-api/` + `com/example/order/Order.java`); the most specific
     match wins.
  2. Count the changed lines the report marks covered or missed. Lines no report
     knows (comments, blank lines, configuration, other languages) count neither
     way.
  3. Return the percentage (rounded down for display; the gate compares the exact
     value) and the files with their first 50 uncovered lines, most uncovered
     first.
  4. The result status is `COMPUTED`, `NO_RUN`, `NO_LINE_DATA` or
     `NO_CHANGED_LINES`.

  `GET /api/v1/qa/projects/{key}/new-code-coverage?base=&head=&suite=` takes
  branches, tags or commits and uses the suite's latest coverage run at head.
- **Trend.** `GET /api/v1/qa/projects/{key}/coverage?suite=&limit=` returns line
  and branch percentages per run, oldest first. The Quality page's "Coverage"
  section shows:
  - each suite's latest line and branch coverage;
  - a line-coverage chart (shared `MetricTrend` with P38: hover text and a table
    view);
  - a "New-code coverage" form (base, head, suite) that lists uncovered changed
    lines.
- **Gate.** `minNewCodeCoverage: {suite: percent}` for required suites. At a
  commit, the gate compares against the project's default branch (merge base):
  - below the minimum fails, with the exact numbers;
  - a run without line data fails;
  - if nothing instrumented changed (for example a commit already on the default
    branch), the check is skipped with a note;
  - if the changes cannot be read, the check fails rather than passing silently.

  The gate form has a "Minimum new-code coverage" field.

## Trade-off Analysis

- **Suffix matching instead of source roots.** JaCoCo does not record source
  directories, so EAHP matches the package path against repository paths inside
  the report's module. This works for standard Maven and Gradle layouts and fails
  closed: unmatched files are not counted.
- **Merge-base diff.** Comparing with the merge base attributes only the branch's
  own changes to it, not changes that landed on the target branch meanwhile.
- **Arrays in Postgres.** One row per file with two integer arrays keeps large
  reports compact and fast to load for one run.

## Production Considerations

- V32 adds `qa_coverage_lines` and `qa_quality_gates.min_new_code_coverage`. Line
  rows are deleted with their run.
- New-code checks read the git mirror, which is local after the first fetch, so the
  gate stays fast.

## Bilingual Interview Q&A

Q: Total coverage is 42%. How do you still stop untested code from being merged?

中文：记录每行覆盖数据，用 `git diff -U0 base...head` 取出变更行，只统计覆盖报告认识的行，算出"新代码覆盖率"。门禁对必需套件设置最低新代码覆盖率；没有行数据或低于阈值就失败，并列出未覆盖的变更行，便于修复。

English: Record per-line coverage, take the changed lines from
`git diff -U0 base...head`, and count only the lines the reports know. The result
is new-code coverage. The gate sets a minimum per required suite. Missing line data
or a value below the minimum fails, and the uncovered changed lines are listed so
they are easy to fix.

## Verification

- `CoverageLinesTest`:
  - JaCoCo and Cobertura lines are read, including partly covered lines and
    duplicates;
  - modules are found from report paths;
  - path matching follows the module and the suffix, and the most specific entry
    wins.
- `QaSuiteToolApiTest.newCodeCoverageIsComputedFromChangedLinesAndEnforcedByTheGate`:
  - line data is stored;
  - 2 of 3 changed lines are covered (66.6%), and line 11 is listed as uncovered;
  - the trend endpoint works;
  - the gate fails at 80% and passes at 60% with a note;
  - when nothing instrumented changed, the check is skipped;
  - a run without line data fails;
  - invalid gate entries are rejected.
- Frontend: the coverage trend and the new-code check (request parameters, result,
  uncovered lines), and the gate form sends `minNewCodeCoverage`.

## Observed local results

- Deployed with `scripts/deploy.sh` (V32). online-shop `backend-unit` ran at
  `8a2910be`. It ended `TESTS_FAILED` because of the known Testcontainers test
  that needs Docker inside the runner. It still stored line data for 59 source
  files (330 covered and 269 missed lines).
- New-code coverage of the catalog domain change (merge of
  exedeehuashan/online-shop#50, `1a79d55e...8a2910be`, 27 files changed):
  **72.6%**, 61 of 84 instrumented changed lines across 25 changed files. The
  least covered:

  | File | Covered | Missed |
  | --- | --- | --- |
  | `CatalogService.java` | 0 | 9 |
  | `ProductAttribute.java` | 25 | 4 |
  | `CatalogProduct.java` | 8 | 3 |

  Uncovered lines are listed per file.
- The coverage trend for `backend-unit` shows the earlier runs: 59.5% → 55.1% →
  42.1% line coverage as the catalog modules grew.
