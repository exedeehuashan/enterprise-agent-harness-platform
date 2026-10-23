# P34: AI-generated API tests

Status: 方案 4's AI test generation. The model drafts API test cases from a
project's OpenAPI document. Before anything runs, EAHP validates the draft with
the same strict parser it uses for tests written by people (P24).

## Architecture Explanation

- **Request.**
  `POST /api/v1/qa/projects/{key}/ai/api-tests` (OPERATOR) with
  `{specPath, service, ref?, instructions?, maxCases?}`.
  - `specPath` is a relative `.json`, `.yaml` or `.yml` file, read from the project
    repository at the exact commit, up to 400 KiB.
  - `service` names the environment service the tests target.
  - `maxCases` is 1 to 30, default 8.
- **Prompt.**
  - The system prompt describes the P24 format: requests, the five check kinds,
    captures, variables, forbidden headers, and host-free paths.
  - It asks for realistic synthetic data, unique IDs (`eahp-{{$runId}}-{{$uuid}}`),
    create-then-read chains, error cases, and only assertions the document states.
  - The OpenAPI document and the reviewer's instructions go in delimited
    `<openapi>` and `<reviewer-instructions>` sections, marked as data that cannot
    change the rules.
  - The call goes through `AiService` (P33): budget, redaction and strict JSON. The
    answer schema is `{collection: <JSON text>, notes}`.
- **Validation.**
  - The collection's `service` is forced to the requested one.
  - Its key is normalized to start with `ai-`.
  - The case count is checked.
  - The whole collection goes through `ApiCollections.parse`, the same strict
    validation as `.eahp/api-tests.json`.

  A draft that tries to call another host, uses an unknown field or check, or
  breaks a limit is stored as **invalid** with the parser's message. A model answer
  that is not JSON returns `AI_INVALID_ANSWER`.
- **Drafts.**
  - Stored in `qa_ai_test_drafts` (V28) with commit, spec path, service,
    instructions, collection, validity and error, notes, provider, model, tokens and
    author.
  - Listed and read through `GET /api/v1/qa/projects/{key}/ai/api-tests[/{id}]`.
- **Running a draft.**
  - `POST /api/v1/qa/projects/{key}/ai/api-tests/{id}/run {environment}` (OPERATOR)
    creates a task and starts `QA_RUN_API_TESTS` with `{project, draft, environment}`.
  - The tool loads the valid draft, re-validates it, and runs it at the commit it
    was drafted from, against the environment's registered URLs.
  - Evidence, ingestion, history and reports work as for any API test run; the
    suite key is the draft's `ai-…` key.
- **Console.** The Test projects page has an "AI-generated API tests" section:
  - a form for the OpenAPI file, service, ref and focus;
  - a list of drafts showing validity, case count, notes and parser errors;
  - per valid draft, **Run against \<environment\>** and **Download**, which saves an
    `.eahp/api-tests.json` file to review and commit.

## Trade-off Analysis

Generating into EAHP's own declarative format, rather than code, is what makes AI
tests safe to run without review. They cannot execute anything, cannot reach
unregistered hosts, and are bounded by the parser.

Drafts are runnable but not automatically adopted. The useful ones become
permanent by committing the downloaded file, so they are reviewed in a pull request
like any other test.

Asking for the collection as JSON text inside a small schema keeps the structured
output simple for Claude and DeepSeek alike. The real validation is EAHP's parser,
not the provider.

## Production Considerations

- Large OpenAPI documents cost tokens on every generation. The 400 KiB limit and
  the daily budget (P33) bound this.
- Running a draft creates synthetic data in the target environment, as other API
  tests do.
- V28 adds `qa_ai_test_drafts`.

## Bilingual Interview Q&A

Q: Why is it safe to run AI-generated tests?

中文：AI 只能输出 EAHP 的声明式测试格式，必须通过和人工测试相同的严格解析器；路径不能包含主机，服务名被强制为指定服务，请求只能发往审批人登记的环境，不执行任何代码。无效的草稿只会被记录，不能运行。

English: The AI can only produce EAHP's declarative format, which must pass the
same strict parser as tests written by people. Paths cannot contain a host, the
service is forced to the requested one, requests go only to approver-registered
environments, and no code is executed. Invalid drafts are recorded but cannot run.

Q: How do AI-generated tests become part of the regular suite?

中文：在控制台下载草稿，作为 `.eahp/api-tests.json` 的一部分提交到仓库，经过 PR 评审后就和人工编写的集合一样被调度和门禁使用。

English: Download the draft from the console and commit it as part of
`.eahp/api-tests.json`. After PR review it is scheduled and gated like any
collection written by people.

## Verification

- `QaApiTestApiTest` (5 tests) adds a generation case, run against a fake system
  under test with a scripted model:
  - viewers get 403;
  - path traversal, a missing spec and a bad service name get 400;
  - a valid draft is created with an `ai-` key, the forced service, the commit and
    the notes;
  - a draft whose step targets `http://evil.example` is stored invalid with the
    parser's error and cannot run;
  - a non-JSON model answer gets `AI_INVALID_ANSWER`;
  - a valid draft runs against the environment at its commit;
  - drafts are listed, and usage is recorded.
- Frontend: 22 tests, covering generating a draft, showing an invalid one with its
  error, and running a valid one.

## Observed local results

Deployed with `scripts/deploy.sh` (V28), with the mock provider:

1. Generated a draft from online-shop's real
   `frontend/order-web/openapi/order-api.json` at `main` (a prompt of about 2,500
   tokens).
2. The draft was valid.
3. Running it against the UAT environment completed and passed.

With Claude or DeepSeek configured, the same flow drafts real cases from the
document.
