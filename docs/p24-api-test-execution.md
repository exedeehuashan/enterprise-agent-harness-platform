# P24: API test execution against test environments

Status: 方案 4, Sprint 1 decision D-001 (the API-testing execution loop). EAHP now
sends HTTP requests to a deployed system under test, asserts on the responses, and
records the results as evidence. Until now it only ran a repository's own test
suites.

The declarative case format is also the target format for AI test generation
later. A model will write cases in it, and EAHP will run them the same way.

## Architecture Explanation

### Environments (V20)

- `qa_environments` lists, per project, named services and their base URLs; for
  example `uat`: `order-api → http://host.docker.internal:18081`.
- Only approvers register environments, because an environment decides where EAHP
  sends requests:
  - `PUT` and `DELETE /api/v1/qa/admin/projects/{project}/environments/{key}`,
    audited as `ENVIRONMENT_*`;
  - viewers read `GET /api/v1/qa/environments`.
- Base URLs must be `http(s)://host[:port][/path]`, without credentials, query or
  fragment.

### Test file

- `.eahp/api-tests.json` sits in the repository, is read at the run's exact commit,
  and is parsed strictly (`ApiCollections`); unknown fields are rejected.
- A file holds collections; a collection holds cases; a case holds steps.
- Each step has a **request**:
  - method: GET, POST, PUT, PATCH, DELETE or HEAD;
  - a path beginning with `/`;
  - headers, where `Host`, `Content-Length` and hop-by-hop headers are refused;
  - a JSON body.
- Each step has an **expect** block:
  - status: one code or a list;
  - headers: `exists`, `equals` or `matches`;
  - JSON checks on a path such as `$.a.b[0]`: `exists`, `equals`, `matches`, `type`
    or `length`;
  - `maxMillis` for response time.
- A step can **capture** values from the JSON body or from a header.
- **Variables** use `{{name}}`. Built-ins are `{{$uuid}}`, `{{$runId}}` and
  `{{$timestamp}}`. Collection variables are resolved once per case. A string that
  is exactly one variable keeps that variable's JSON type.
- Limits: 20 collections, 200 cases per collection, 20 steps per case, 256 KiB per
  file.

### Execution

- `QA_RUN_API_TESTS` takes `{project, collection, environment, ref?}`.
- Normalization binds the commit, the collection definition and the environment's
  base URLs into the run's arguments, so the run record (and any approval) shows
  exactly what is sent where. The run's `suite` is the collection key.
- **Runner** (`ApiTestRunner`):
  - cases run in order, and independently: captured values are scoped to their case;
  - the first failing step ends its case;
  - a request goes to the base URL plus the path, after normalization;
  - a URL that leaves the base's scheme, host, port or path prefix is refused, so
    `..` and `//host` cannot escape;
  - redirects are never followed;
  - each request times out after 30 s, and response bodies are read up to 1 MiB.
- **Evidence** uses the same format as suite runs, so ingestion, history, triage,
  the release gate, the QA report and comparisons apply unchanged:
  - `summary.json`;
  - `junit-reports.zip`, with one test case per API case, classname set to the
    collection;
  - `console.log`, the HTTP transcript, with bodies cut to 2,000 characters and
    `Authorization`, `Cookie`, `Set-Cookie` and `X-Api-Key` redacted.
- **Outcomes**:
  - `TESTS_FAILED` when a case fails;
  - `ENVIRONMENT_UNAVAILABLE` when every case failed to reach its service;
  - `ENVIRONMENT_CHANGED` when the environment changed after the run was created,
    in which case nothing is sent;
  - `STOPPED`.

### Catalog and console

- `GET /api/v1/qa/projects/{key}/api-tests?ref=` lists the collections at a ref and
  the project's environments. It reports an absent or invalid file explicitly.
- The task page has a Run API tests form: collection, environment, ref and
  timeout, showing where requests will go.
- The Test projects page lists environments, and approvers can add or delete them
  there.

### Deployment

The backend container maps `host.docker.internal` to the host gateway, so it can
reach systems under test that are published on the host, such as the local UAT
stack.

## Trade-off Analysis

A declarative JSON format instead of scripts (JavaScript, Python):

- **Advantages:**
  - Nothing from the repository is executed inside EAHP.
  - Requests are limited to registered origins.
  - A model can generate the format and validate it against the same parser.
- **Cost:** some expressiveness. There are no loops or computed assertions; complex
  flows stay in the project's own test suites.

Running inside the backend, rather than in a container, keeps API tests fast (no
image pull). This is safe because no repository code runs.

## Production Considerations

- API tests create data in the target environment. Point them at test
  environments only; cases should use synthetic, run-unique identifiers (online-shop
  uses `eahp-{{$runId}}-{{$uuid}}`).
- `QA_RUN_API_TESTS` does not require approval; an approver controls where it can
  send requests by registering environments. A production-like environment should
  not be registered.
- A collection key shares the suite namespace in history and gates. Keep it
  distinct from suite keys.
- V20 adds `qa_environments` and the tool definition.

## Bilingual Interview Q&A

Q: How does EAHP prevent API tests from being abused to reach arbitrary hosts (SSRF)?

中文：仓库里的测试只能写服务名和路径，主机地址来自审批人登记的环境；路径拼接后会校验协议、主机、端口和路径前缀，不跟随重定向；环境在运行创建后若被修改，运行直接失败而不发送请求。

English: Tests in the repository name only a service and a path; hosts come from
environments that approvers register. The resolved URL must keep the base's
scheme, host, port and path prefix, and redirects are never followed. If the
environment changes after the run was created, the run fails without sending
anything.

Q: Why a declarative format rather than test scripts?

中文：声明式格式不执行仓库代码，可以严格校验和限制；同时它也是后续 AI 生成测试用例的目标格式，模型生成的用例可以用同一个解析器校验后执行。

English: A declarative format executes no repository code and can be strictly
validated and bounded. It is also the target format for AI-generated tests later:
cases a model writes can be validated by the same parser and executed the same way.

## Verification

- `ApiCollectionsTest`:
  - strict parsing: schema version, paths, methods, forbidden headers, status codes,
    check kinds, JSON paths, service names, unknown fields;
  - URL resolution refuses `..`, `//host`, spaces and backslashes.
- `QaApiTestApiTest` runs against a real local HTTP server:
  - environments are approver-only, validated and audited;
  - the catalog endpoint reports found, invalid and absent files;
  - a collection with passing, failing, redirect and template-error cases produces
    `TESTS_FAILED`, JUnit XML, the summary and a redacted transcript;
  - captured IDs and run-unique customer IDs are used, and redirects are not
    followed;
  - ingestion records 4 tests, 2 failed;
  - missing environments and collections, and an unsafe file, are rejected at
    start;
  - a changed environment sends nothing, and an unreachable one gives
    `ENVIRONMENT_UNAVAILABLE`.
- `QaSuiteToolApiTest` (28 tests) and `PullRequestTriggerApiTest` (13 tests) are
  unchanged.
- `ApprovalSecurityApiTest` now counts 5 tools.
- Frontend: 22 tests, including the Run API tests form and the environments panel.

## Observed local results

Deployed with `scripts/deploy.sh` (V20). Steps on the live stack:

1. Registered environment `uat` for online-shop:
   `order-api → http://host.docker.internal:18081` and
   `catalog-api → http://host.docker.internal:18082` (`online-shop-uat-main`).
2. Ran the collections at the online-shop branch (#54) that adds
   `.eahp/api-tests.json`:
   - `catalog-api-smoke`: **3 of 3 passed**;
   - `order-api-smoke`: **5 of 5 passed**. This covers idempotent create and
     replay, detail and list, confirm with a stale-retry 409, cancel and
     confirm-after-cancel 409, and validation 422 and 400.
3. Both results were ingested and appear in the commit's QA report.

While writing the cases, probing showed that cancelling a CONFIRMED order succeeds.
That is by design in online-shop's domain model. Only confirming a cancelled order
is an invalid transition, so the cases assert exactly that.
