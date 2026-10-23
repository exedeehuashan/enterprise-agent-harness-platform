# 配置 AI 模型 / Configuring the AI model

EAHP 在你配置 API key 之前不会调用任何外部模型。默认是 `mock`（确定性答案、没有网络请求）。

EAHP calls no external model until you configure a key. The default is `mock`:
deterministic answers and no network calls.

## 1. 把 key 放在 `.env` / Put the key in `.env`

在部署目录（`enterprise-agent-harness-platform/.env`，权限 600，不进 git）中设置以下其中一组：

Set one of these groups in the deployment's `.env`
(`enterprise-agent-harness-platform/.env`, mode 600, not in git):

**Claude（Anthropic）**

```dotenv
EAHP_AI_PROVIDER=anthropic
ANTHROPIC_API_KEY=sk-ant-...
# 可选 / optional:
ANTHROPIC_MODEL=claude-opus-5-5
ANTHROPIC_EFFORT=medium          # low | medium | high | xhigh | max
```

**DeepSeek**

```dotenv
EAHP_AI_PROVIDER=deepseek
DEEPSEEK_API_KEY=sk-...
# 可选 / optional:
DEEPSEEK_MODEL=deepseek-chat
```

**每天的 token 上限（两种都适用）/ Daily token cap (both providers)**

```dotenv
EAHP_AI_DAILY_TOKEN_BUDGET=500000
```

## 2. 重新部署 / Redeploy

```bash
./scripts/deploy.sh
```

如果选了真实模型却没填 key，后端启动会失败，不会悄悄退回 mock。

If a real provider is selected without its key, the backend fails to start instead
of silently falling back to the mock.

## 3. 确认 / Check

- 控制台 → Administration → "AI model"，显示 provider、model 和今天的用量。

  Console → Administration → "AI model" shows the provider, model and today's
  usage.
- 或者 / or: `GET /api/v1/ai/status`

## 用到模型的功能 / What uses the model

| 功能 / Feature | 位置 / Where |
| --- | --- |
| 任务规划 / Task planning (P4) | 任务页 "Plan" / task page "Plan" |
| 失败分析 / Failure analysis (P33) | 失败的 QA 运行页 "Analyse with AI" / failed QA run page "Analyse with AI" |
| API 测试生成 / API test generation (P34) | 测试项目页 "AI-generated API tests" / Test projects page "AI-generated API tests" |

## 说明 / Notes

- **Claude** uses the official Anthropic Java SDK with structured outputs, so
  answers follow the schema. The server-side refusal fallback is on: a declined
  request is retried on the recommended model. Set
  `EAHP_AI_ANTHROPIC_FALLBACKS=false` to turn it off.
- **DeepSeek** uses its JSON mode. Every answer from either provider is validated
  by EAHP before it is stored or used.
- **Redaction.** Prompts are redacted for common secrets (tokens, passwords, keys,
  JWTs) before they leave EAHP.

## GitHub 回写（可选）/ GitHub write-back (optional)

在 `.env` 中设置 `EAHP_GITHUB_WRITE_TOKEN`。这个 fine-grained token 需要被测仓库的
"Commit statuses: write" 和 "Pull requests: write" 权限。然后在控制台的 Test projects
页面，为对应项目打开 "GitHub write-back"。

Set `EAHP_GITHUB_WRITE_TOKEN` in `.env`: a fine-grained token with "Commit
statuses: write" and "Pull requests: write" on the tested repositories. Then turn
on "GitHub write-back" for the project on the console's Test projects page.
