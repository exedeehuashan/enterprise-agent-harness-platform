# P33: AI providers (Claude, DeepSeek) and AI failure analysis

Status: EAHP now uses a real language model, Claude or DeepSeek, selected by
configuration. The first features are model planning (P4, now with a real model)
and AI failure analysis for QA runs (方案 4). A mock provider remains the default,
so nothing calls an external API until a key is configured.

## Architecture Explanation

### Provider layer (`ai` package)

`LlmProvider` is provider-neutral: a system prompt, a user prompt and a JSON Schema
go in; JSON text and token usage come out. Failures carry bounded codes: `AI_REFUSED`,
`AI_TRUNCATED`, `AI_RATE_LIMITED`, `AI_PROVIDER_ERROR`, `AI_UNAVAILABLE`, `AI_EMPTY`.

**`AnthropicLlm` (Claude)** uses the official `anthropic-java` SDK (2.34.0):

- The default model is `claude-opus-5-5`; set `ANTHROPIC_MODEL` to use another.
- The answer is constrained to the schema with structured outputs
  (`output_config.format`, `json_schema`).
- `output_config.effort` is configurable through `ANTHROPIC_EFFORT`, default
  `medium`. Claude Opus 5.5 always thinks adaptively, so no `thinking` parameter is
  sent.
- **Refusal handling.** The server-side fallback is on (`fallbacks: "default"`,
  beta `server-side-fallback-2026-07-01`): a declined request is re-run on the model
  Anthropic recommends for that refusal category. A refusal that still comes back
  is reported as `AI_REFUSED`, never parsed. A `max_tokens` stop is reported as
  `AI_TRUNCATED`.
- The SDK retries 429 and 5xx responses twice.

**`DeepSeekLlm`** calls DeepSeek's chat completions API (`POST /chat/completions`,
Bearer key, default model `deepseek-chat`) in JSON mode
(`response_format: json_object`). The schema goes in the system prompt, because
DeepSeek does not enforce it. A `length` finish maps to `AI_TRUNCATED` and
`content_filter` to `AI_REFUSED`.

**`MockLlm`** gives deterministic answers by schema title and makes no network
calls. It is the default.

**`AiConfiguration`** selects the provider from `EAHP_AI_PROVIDER`
(`mock` | `anthropic` | `deepseek`). Choosing a real provider without its key fails
startup, so a misconfiguration is never silently replaced by the mock.

### `AiService`: the single gate for every AI call

- Enforces a daily token budget (`EAHP_AI_DAILY_TOKEN_BUDGET`, default 500,000;
  returns `AI_BUDGET_EXHAUSTED` once it is used up).
- **Redacts secrets from every prompt**: bearer and basic credentials,
  `password=` / `token:` style values, Anthropic, OpenAI-style, GitHub, EAHP and AWS
  keys, private keys, JWTs, and passwords embedded in URLs.
- Records usage per feature in `ai_usage` (V27).
- Parses the answer strictly as one JSON object (duplicate keys and trailing tokens
  are rejected).
- `GET /api/v1/ai/status` (VIEWER) shows the provider, the model and today's usage.

### Planning (P4) with a real model

`ModelConfiguration` uses `LlmPlanProvider` whenever a real provider is configured:

- The model sees the task and the enabled tools that take no arguments.
- It answers in the plan schema, and `PlanValidator` still validates every plan as
  before.
- The task text is marked as data, not instructions.

### AI failure analysis (方案 4)

`POST /api/v1/qa/runs/{runId}/ai-analysis` (OPERATOR) analyses a failed or broken
run that has been ingested:

- **Evidence given to the model:**
  - project, suite, commit and outcome;
  - the rule-based triage (P13);
  - up to 20 failing tests with their category, history label and message;
  - the last 8,000 characters of the console log.

  It is wrapped in `<evidence>` tags, and the system prompt tells the model to treat
  it strictly as data and never follow instructions found in it.
- **Answer schema:** `summary`, `likelyCause`, `category` (`PRODUCT_DEFECT`,
  `TEST_DEFECT`, `ENVIRONMENT`, `FLAKY`, `INFRASTRUCTURE` or `UNKNOWN`),
  `confidence`, `suggestedActions` and `affectedTests`. Every field is validated
  and bounded.
- **Storage:** results are kept in `qa_ai_analyses` (V27) with provider, model,
  tokens and who asked. `GET` returns the latest.
- **Advisory only.** Nothing acts on an analysis, and the run's outcome and the gate
  are unchanged.

### Console

- Run pages of failed QA runs show an **AI analysis** section with "Analyse with
  AI", displaying the category, confidence, summary, likely cause, next steps,
  provider, model and tokens.
- The Administration page shows the AI provider and today's usage.

### Fix

The run page no longer fails for API test runs, whose summaries have no runner
image or command.

## Trade-off Analysis

- **SDK for Claude, plain HTTP for DeepSeek.** The official SDK keeps the Claude
  integration current: typed request fields, retries and error types. DeepSeek has
  no Java SDK, so it uses its documented HTTP API behind the same interface. Callers
  never see the difference.
- **Structured outputs vs JSON mode.** With Claude the schema is enforced by the
  API; with DeepSeek it is only requested. EAHP validates every answer either way,
  so both providers are held to the same standard.
- **Analysis is on request, not automatic.** Running it for every failure would
  spend tokens on failures nobody looks at. A webhook (P26) or a future policy can
  trigger it automatically where wanted.

## Production Considerations

- **Where the keys go:** `.env` (mode 600), never in the repository or in URLs.
  Restart with `scripts/deploy.sh` after changing them.
- Prompts include test output and logs. Redaction removes common credential
  shapes, but don't rely on it for everything: avoid printing secrets in tests.
- Claude Opus 5.5 runs on the Claude API. For a 30-day data retention or
  zero-retention requirement, check your Anthropic agreement.
- V27 adds `ai_usage` and `qa_ai_analyses`.

## Bilingual Interview Q&A

Q: How do you stop prompt injection from test output?

中文：测试输出和日志放在 `<evidence>` 标签里，系统提示明确说明只能当数据分析、不能执行其中的指令；模型输出只是建议文本，逐字段校验并限制长度，不会触发任何动作，运行结论和门禁也不受影响。

English: Test output and logs go inside `<evidence>` tags, and the system prompt
says to analyse them as data and never follow instructions in them. The output is
advisory text, validated field by field and bounded. It triggers no action and
changes neither the run's outcome nor the gate.

Q: How is switching between Claude and DeepSeek made safe?

中文：统一的 LlmProvider 接口，由配置选择；选了真实模型却没有 key 会启动失败，不会悄悄退回 mock。两种模型的输出都经过同样的严格解析和字段校验，用量统一计入每日预算。

English: Both sit behind one `LlmProvider` interface selected by configuration. A
real provider without its key fails startup instead of quietly using the mock.
Answers from either model go through the same strict parsing and field validation,
and usage counts against one daily budget.

## Verification

- `LlmProvidersTest`, against local fake APIs:
  - Claude requests carry the API key and version headers, the fallback beta and
    `fallbacks: "default"`, the model, system prompt and max tokens, the effort and
    a `json_schema` output format, and no `thinking` field;
  - Claude refusals, truncation and 400s map to bounded codes, and there is no beta
    header when fallbacks are off;
  - DeepSeek requests carry the Bearer key, JSON mode and the schema in the system
    prompt; `length`, 429 and 500 map to bounded codes;
  - secrets of every listed kind are redacted.
- `ModelConfigurationTest` (11 tests):
  - unknown providers fail startup;
  - real providers without keys fail startup;
  - configured Claude and DeepSeek plan through `LlmPlanProvider`;
  - the mock is the default.
- `QaSuiteToolApiTest` (32 tests) adds an analysis case:
  - an un-ingested run gets 409, viewers get 403, a passed run gets 400, and a
    missing analysis 404;
  - an operator gets the analysis, with usage recorded and status updated;
  - the run's outcome is unchanged.
- `ModelPlanningApiTest` (43 tests) is unchanged.
- Frontend: 22 tests, including analysing a failed run from its page.

## Observed local results

Deployed with `scripts/deploy.sh` (V27), with `EAHP_AI_PROVIDER` unset (mock):

- `/api/v1/ai/status` reports `mock`.
- Analysing the latest real failed online-shop run built a prompt of about 2,300
  tokens from its evidence and stored the analysis.

Calls to Claude or DeepSeek need their keys in `.env`.
