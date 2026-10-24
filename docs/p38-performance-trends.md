# P38: Performance trends and gate limits

Status: 方案 4. Performance suites (k6, P32) used to give only pass or fail from the
script's own thresholds. EAHP now records each run's latency, throughput and error
rate, shows the trend, and lets the release gate enforce limits, including
"no more than N% slower than recently".

## Architecture Explanation

- **Declaring it.** A suite in `.eahp/qa-suites.json` adds
  `"performance": "k6-summary.json"`, a relative `.json` path, and writes that
  file with `k6 run --summary-export=k6-summary.json …`.
- **Reading it.** After the run, `PerformanceReports` reads the file (at most
  1 MiB, no symbolic links, inside the checkout). Every value must be a finite,
  non-negative number, and rates must be at most 1. It writes `performance.json`
  as evidence:
  - `requests` and `requestsPerSecond`;
  - `avgMs`, `p90Ms`, `p95Ms` and `maxMs`;
  - `errorRate`, k6's `http_req_failed` rate;
  - `checksRate`.

  A missing or invalid file gives `{"error": "…"}` instead.
- **Storing it (V31).** Ingestion copies valid summaries to `qa_performance`
  (run, project, suite, commit, outcome, metrics).
- **Trend.** `GET /api/v1/qa/projects/{key}/performance?suite=&limit=` returns
  measured runs, oldest first. On the console's Quality page, the "Performance"
  section shows each suite's latest p95, error rate, throughput and request count,
  and a p95 line chart with the gate limit, per-point hover text and a table view.
- **Gate limits.** `qa_quality_gates.performance` maps a required suite to
  optional limits:
  - `maxP95Ms`: the run's p95 must not exceed it;
  - `maxErrorRate` (0..1): the HTTP error rate must not exceed it, and a missing
    rate fails;
  - `maxP95IncreasePercent`: p95 must not be more than this much above the
    **median p95 of the suite's last five passing runs at other commits**. It
    needs at least three such runs; otherwise it is skipped, with a note.

  A required suite with limits that reported no performance results fails. Like
  coverage, a quarantine waiver does not waive performance limits. Gate checks
  show `p95Ms` per suite.
- **Administration.** `PUT /api/v1/qa/admin/projects/{key}/gate` accepts
  `performance: {suite: {maxP95Ms, maxErrorRate, maxP95IncreasePercent}}`.
  Unknown fields, non-required suites, empty limits and out-of-range values are
  rejected, and changes are audited. The console's gate form takes
  `performance: p95=500, errors=0.01, increase=50`, and a malformed entry stops
  the save.

## Trade-off Analysis

- **k6's summary export instead of a custom reporter.** It is a stable,
  documented k6 output that needs only a command-line flag in the repository.
- **Median of recent passing runs as the baseline.** A single earlier run is
  noisy, and the median ignores one outlier. Requiring three runs avoids judging
  from too little history.
- **p95, not average.** Tail latency is what users notice, and averages hide it.

## Production Considerations

- Run performance suites against a stable environment, either a registered one or
  a per-commit environment (P36), or the trend mixes environments.
- V31 adds `qa_performance` and `qa_quality_gates.performance`.

## Bilingual Interview Q&A

Q: How do you catch a performance regression in CI without flaky absolute thresholds?

中文：每次 k6 运行都记录 p95、吞吐和错误率。门禁既可以设绝对上限，也可以设"相对最近 5 次通过运行的 p95 中位数不得超过 N%"。历史不足 3 次时只给提示不判失败，避免数据太少导致误判。

English: Every k6 run records p95, throughput and error rate. The gate can set
absolute limits, or "no more than N% above the median p95 of the last five passing
runs". With fewer than three earlier runs it adds a note instead of failing, so
too little history never decides the verdict.

## Verification

- `QaSuiteToolApiTest.performanceIsRecordedTrendedAndEnforcedByTheGate`:
  - `performance.json` is produced from a k6 summary and ingested;
  - the trend API is ordered;
  - each limit fails the gate with an exact reason (p95, error rate, +86% against
    a 2.15 ms median) and passes when relaxed;
  - the increase check is skipped with a note when history is short;
  - a missing summary fails a gate with limits;
  - invalid limits and an outside path are rejected.
- Frontend: the Quality page shows the p95 trend with the gate limit, and the
  gate form validates and sends performance limits.

## Observed local results

- Deployed with `scripts/deploy.sh` (V31). online-shop's `performance` suite now
  exports `k6-summary.json` (exedeehuashan/online-shop#60). It ran three times
  against a per-commit environment (P36) at `1deec0db`:

  | Run | p95 | Error rate | Throughput | Requests |
  | --- | --- | --- | --- | --- |
  | 1 | 2.45 ms | 0% | 1,285 req/s | 57,835 |
  | 2 | 1.63 ms | 0% | 1,715 req/s | 77,178 |
  | 3 | 2.01 ms | 0% | 1,436 req/s | 64,635 |

  `GET …/performance` returned the three points in order. The environment was
  then stopped, leaving nothing behind.
