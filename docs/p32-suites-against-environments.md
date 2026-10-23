# P32: Suites against environments (performance tests)

Status: 方案 4. A repository suite can run against a registered environment. Its
service URLs are passed to the suite's container as environment variables. This
brings in tests that need a deployed system but are not declarative API tests,
such as online-shop's k6 performance baseline. With it, every test type online-shop
has is run by EAHP.

## Architecture Explanation

- **Declaration.** A suite maps variables to services:

  ```json
  "environment": {"ORDER_API": "order-api", "CATALOG_API": "catalog-api"}
  ```

  The parser enforces:
  - variable names are upper case;
  - runner variables (`HOME`, `PATH`, `CI`, `LD_*`, `JAVA_TOOL_OPTIONS`,
    `NODE_OPTIONS`, `MAVEN_*`, `TESTCONTAINERS_*`) cannot be reused;
  - values must be service names;
  - the suite needs network egress;
  - selection (P19/P20) is not allowed with an environment.
- **Starting a run.** `QA_RUN_SUITE` requires an `environment` argument for such a
  suite and refuses it for any other suite. The URLs are resolved from the
  environment and bound into the run's arguments as `services` (variable → URL),
  so the approval digest and the audit trail show exactly where traffic goes. An
  environment missing one of the services is rejected.
- **Execution.**
  - The container gets `-e VAR=url` for each service and
    `--add-host host.docker.internal:host-gateway`, so it can reach systems
    published on the runner host.
  - If the environment changed after the run was created, the run fails with
    `ENVIRONMENT_CHANGED` before any container starts.
- **Where they run.**
  - **Pull requests:** these suites are skipped (`NEEDS_ENVIRONMENT`), because they
    would test what is deployed, not the PR's code.
  - **Schedules:** they run when the schedule names an `environment` (P31). The
    scheduler runs the suite with that environment when the key is such a suite,
    and otherwise treats the key as an API test collection.
- **Console.** The Run test suite form shows an Environment picker for these suites.
- **online-shop `performance`.**
  - Runs `grafana/k6:0.54.0` with `run --quiet tests/performance/scenarios.js`,
    against `ORDER_API` and `CATALOG_API`.
  - Results come from the exit code: k6 exits non-zero when the script's thresholds
    are missed (checks > 99%, HTTP errors < 1%).
  - The k6 image was added to online-shop's policy (an audited admin change).

## Trade-off Analysis

Passing URLs as environment variables keeps EAHP neutral about the tool: k6,
Gatling, Postman/Newman or a Playwright suite against a real stack all read
variables. The alternative of rewriting tool-specific configuration would tie EAHP
to each tool.

Performance results are recorded as pass or fail against the script's own
thresholds; the detailed numbers stay in the console log artifact. Tracking p95
over time as a metric is a possible extension.

## Production Considerations

- Performance runs generate load. Point them at test environments only and prefer
  nightly schedules.
- Container access to the host goes through `host-gateway`, which is only as open
  as the services published on the host.

## Bilingual Interview Q&A

Q: How does EAHP run performance tests without knowing the tool?

中文：套件声明"变量名到服务名"的映射，运行时从审批人登记的环境解析出地址，作为环境变量注入容器；k6、Gatling 等工具都能读环境变量，而地址会被绑定在运行参数里，环境变了就拒绝运行。

English: The suite declares a mapping from variable names to services. At run time
EAHP resolves the URLs from an approver-registered environment and injects them as
environment variables, which k6, Gatling and similar tools all read. The URLs are
bound into the run's arguments, and a changed environment stops the run.

## Verification

- `QaSuiteToolApiTest` (31 tests) adds an environment-suite case:
  - a run without an environment, or with an unknown one, is rejected;
  - the URLs are bound into the arguments and passed as `-e` with host-gateway;
  - a changed environment gives `ENVIRONMENT_CHANGED` and no container starts;
  - a reserved variable, `network: none` and bad service names are rejected.
- `PullRequestTriggerApiTest` (13 tests) and `QaApiTestApiTest` (4 tests) are
  unchanged.
- Frontend: 22 tests.

## Observed local results

Deployed with `scripts/deploy.sh`. On the online-shop branch that declares
`performance` (#57), EAHP ran k6 against the UAT environment for 45 s:

- 42,706 requests, about 949 per second;
- all checks passed: `catalog 200`, `orders 200`, `created/replayed`, `hot orders`,
  `hot catalog`, `conflict fixture available` and `optimistic conflict 409`;
- `http_req_failed` 0%;
- `http_req_duration` p95 2.56 ms.

The suite passed.
