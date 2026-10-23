# P28: Metrics and domain modules

Status: generic EAHP capability. EAHP can be monitored like any other service, and
its domain agents (方案) are explicit, pluggable modules. 方案 4 is the first.

## Architecture Explanation

### Metrics

- Spring Boot Actuator with the Prometheus registry.
- `GET /actuator/health` is public and returns status only, without details.
- `GET /actuator/prometheus` requires a VIEWER token, for example a named token
  issued for the monitoring system (P27). No other actuator endpoint is exposed.
- Besides JVM, HTTP and pool metrics, `EahpMetrics` registers gauges that are
  evaluated at scrape time:

  | Gauge | Meaning |
  | --- | --- |
  | `eahp_runs{status}` | Runs by status |
  | `eahp_runs_oldest_queued_seconds` | Age of the oldest queued run (queue backlog) |
  | `eahp_approvals_pending` | Runs waiting for an approver |
  | `eahp_webhooks_failing`, `eahp_webhooks_paused` | Webhook health |
  | `eahp_qa_results_24h{outcome}` | QA results ingested in the last 24 hours |

  If the database is unavailable during a scrape, the gauges report NaN, so
  Prometheus records a gap rather than a misleading zero.

### Domain modules

- `DomainModule` (`key`, `name`, `description`, `tools`, `canDisable`) declares
  what a domain contributes. `CoreModule` (tasks, runs, approvals, planning,
  artifacts, audit, webhooks, tokens; it cannot be disabled) and `QaModule`
  (方案 4: `QA_RUN_SUITE`, `QA_RUN_PRIVILEGED_SUITE`, `QA_RUN_API_TESTS`) are the
  first two.
- `ModuleRegistry` refuses duplicate keys and a tool claimed by two modules.
- `domain_modules` (V23) stores each module's on/off state. Switching a module off
  (`PUT /api/v1/admin/modules/{key}/enabled`, APPROVER):
  - disables its tools, so no new runs of them start;
  - pauses its background jobs: for QA, the GitHub pull request poller and
    schedules.

  Switching it on re-enables its tools. Ingestion keeps running, so runs that
  already finished still record their evidence.
- `GET /api/v1/modules` (VIEWER) lists modules, their state and their tools.
- A future 方案 1 or 方案 8 adds its own `DomainModule` bean and tools without
  touching the core.

### Console

A new **Administration** page for approvers brings together:

- domain modules, with switch on/off;
- API tokens;
- webhooks;
- storage and retention.

These panels used to sit on the Test projects page.

### Fix carried over from #30

When an exit-code suite fails, EAHP builds the failure detail from the console log.
It now reads only the last 16 KiB of the log and replaces malformed UTF-8, so a huge
or binary log can neither exhaust memory nor prevent the result from being
recorded.

## Trade-off Analysis

Gauges that query the database at scrape time need no counters wired into every
code path, so they can never disagree with the data. The cost is a handful of small
count queries per scrape (every 15 to 60 s typically).

Switching a module off works through tool definitions and job checks rather than
removing its beans. That is reversible at runtime with no restart, and the module's
data and API stay readable.

## Production Considerations

- Scrape with a named VIEWER token over the backend port (`127.0.0.1:8080` in the
  compose setup).
- Re-enabling a module re-enables all of its tools. Per-tool disables made earlier
  must be reapplied.
- V23 adds `domain_modules`.

## Bilingual Interview Q&A

Q: How do new domain agents (方案) plug into EAHP?

中文：每个领域实现一个 DomainModule，声明自己的工具和后台任务；工具通过统一的 ToolExecutor 插件接口执行，审批、审计、证据、门禁都由平台提供。注册表保证工具不重复，审批人可以整体开关一个领域。

English: Each domain implements a `DomainModule` declaring its tools and jobs. Its
tools run through the common `ToolExecutor` plug-in interface, and the platform
supplies approvals, audit, evidence and webhooks. The registry rejects tools
claimed twice, and an approver can switch a whole domain on or off.

## Verification

- `OpsModulesApiTest`:
  - health is public;
  - Prometheus needs a token, and other actuator endpoints are not exposed;
  - EAHP gauges and JVM metrics appear;
  - modules list core first and QA with 3 tools;
  - only approvers can switch modules; core cannot be switched off;
  - switching QA off disables its tools and switching it on restores them;
  - an unknown module returns 404.
- `ToolPluginApiTest` (13 tests) and `ApprovalSecurityApiTest` (31 tests) are
  unchanged.
- `QaSuiteToolApiTest` (29 tests) now uses a 300 KB console with a non-ASCII byte
  for the failing exit-code case.
- Frontend: 22 tests. The admin test now covers the Administration page: storage,
  modules (switching QA off), webhooks and tokens.

## Observed local results

Deployed with `scripts/deploy.sh` (V23):

- `/actuator/health` returned `{"status":"UP"}`.
- `/actuator/prometheus` returned 401 without a token. With a viewer token it
  returned, for example:
  - `eahp_runs{status="COMPLETED"} 35`;
  - `eahp_qa_results_24h{outcome="PASSED"} 31` and `{outcome="TESTS_FAILED"} 5`;
  - `eahp_approvals_pending 0`;
  - `eahp_webhooks_failing 0`.
- `/api/v1/modules` listed `core` (demo tools) and `qa` (the three QA tools), both
  enabled.
