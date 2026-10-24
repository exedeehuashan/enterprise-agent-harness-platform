# P41: Notification integrations (DingTalk, WeCom, Feishu, Slack, email)

Status: generic EAHP capability on top of P26 webhooks. Teams want QA results and
approval requests where they already talk: a DingTalk (钉钉), WeCom (企业微信),
Feishu (飞书) or Slack group, or email. Each previously needed a relay service in
front of EAHP's signed JSON webhooks. Now a subscription has a **format**.

## Architecture Explanation

- **One mechanism.** A subscription in `webhook_subscriptions` gains `format`
  (V34): `generic` (P26 signed JSON), `slack`, `dingtalk`, `wecom`, `feishu` or
  `email`. All formats share P26's audit-log cursor: ordered, at-least-once
  delivery, exponential backoff, and a pause after 20 consecutive failures.
- **Messages** (`ChatMessages.summarize`) turn each event into one title, a body
  and a link (`EAHP_PUBLIC_URL/runs/<id>`):
  - `QA_RESULT_INGESTED`: "✅ QA passed: online-shop/backend-unit @ 8a2910bef008",
    or "❌ QA tests failed: …";
  - `APPROVAL_REQUESTED`: "🔐 Approval needed: QA_RUN_ENVIRONMENT_TESTS for …";
  - `RUN_*`: run status and error code;
  - `QA_QUARANTINE_SUGGESTED` (P40);
  - anything else: the action name.

  Messages contain only EAHP's own validated keys (project, suite, tool, status,
  codes), never free text from repositories, tasks or test output.
- **Platforms.**

  | Format | Body | URL host (https only) | Signing | Success check |
  | --- | --- | --- | --- | --- |
  | `slack` | `{"text": …}` with an `<url\|Open in EAHP>` link | `hooks.slack.com` | – | HTTP 2xx |
  | `dingtalk` | `msgtype: markdown` | `oapi.dingtalk.com` | optional 加签: `timestamp` and `sign = Base64(HMAC-SHA256(secret, timestamp+"\n"+secret))` in the URL | `errcode == 0` |
  | `wecom` | `msgtype: markdown` | `qyapi.weixin.qq.com` | – (the key is in the URL) | `errcode == 0` |
  | `feishu` | `msg_type: text` | `open.feishu.cn`, `open.larksuite.com` | optional: `timestamp` and `sign = Base64(HMAC-SHA256(key = timestamp+"\n"+secret, ""))` in the body | `code == 0` |
  | `email` | plain-text mail | – | SMTP (`EAHP_SMTP_*`) | accepted by the server |

  Chat robots answer HTTP 200 even when they reject a message (wrong signature,
  revoked token). Their answer, at most 64 KiB, is therefore checked and a
  rejection counts as a failed delivery, for example
  `error 310000: sign not match`.
- **Configuration.**
  - Chat robots need nothing in `.env`: approvers add them on the Administration
    page ("Notifications") with the robot URL and, for DingTalk and Feishu, the
    optional signing secret.
  - The robot secret is stored server-side and never returned. Only generic
    webhooks get a generated secret, shown once.
  - Email needs `EAHP_SMTP_HOST`, `EAHP_SMTP_PORT`, `EAHP_SMTP_USERNAME`,
    `EAHP_SMTP_PASSWORD`, `EAHP_SMTP_FROM` and `EAHP_SMTP_STARTTLS`. Email
    subscriptions list 1..10 recipients and are refused when SMTP is not
    configured.
- **Test message.** `POST /api/v1/admin/webhooks/{id}/test` sends a synthetic
  `EAHP_TEST` event right away. The attempt is recorded under deliveries and the
  console shows whether it worked.
- **Console.** The Administration page's "Notifications" panel:
  - picks the destination (webhook, Slack, DingTalk 钉钉, WeCom 企业微信, Feishu
    飞书, Email);
  - asks for a URL or recipients, and a signing secret where the platform supports
    one;
  - shows each subscription's format and health, masks the token in robot URLs,
    and offers "Send test", "Pause" and "Delete".

## Trade-off Analysis

- **Formats on the existing subscription rather than separate integrations.**
  Ordering, retries, pausing and the delivery log come for free and behave the
  same for every channel.
- **Fixed platform hosts.** A chat subscription can only post to that platform.
  Any other URL must be a generic, signed webhook, so the format cannot be used
  to send unsigned requests to arbitrary hosts.
- **Short summaries, no free text.** Chat messages are read by many people, and
  test output or task text could carry markup or secrets. The run link leads to
  the full evidence behind EAHP's access control.

## Production Considerations

- V34 adds `webhook_subscriptions.format`. Existing subscriptions are `generic`.
- `spring-boot-starter-mail` is new. The mail health indicator is off, so an
  unreachable SMTP server never marks EAHP unhealthy.
- Set `EAHP_PUBLIC_URL` so messages link to the console.

## Bilingual Interview Q&A

Q: How do you add chat notifications without a pile of one-off integrations?

中文：在已有的 webhook 订阅上加 `format` 字段，钉钉、企业微信、飞书、Slack、邮件共用同一套按审计日志游标、按顺序、至少一次、退避重试、连续失败暂停的投递机制。各平台只是消息体和签名不同。机器人返回 200 但带错误码时也算投递失败。

English: Add a `format` to the existing webhook subscription. DingTalk, WeCom,
Feishu, Slack and email share one delivery mechanism: an audit-log cursor, ordered
at-least-once delivery, retries with backoff, and a pause after repeated failures.
Platforms differ only in message body and signing. A robot that answers 200 with
an error code still counts as a failed delivery.

## Verification

- `ChatMessagesTest`:
  - events are summarized and links built;
  - each platform's body is checked;
  - DingTalk and Feishu signatures match values computed independently with
    Python's `hmac`;
  - error codes in 200 responses count as failures.
- `WebhookApiTest` (5):
  - host, scheme, secret and recipient rules per platform;
  - a DingTalk robot (local) receives a signed markdown message with the run
    link, and a later `errcode` rejection is recorded and retried;
  - email goes through a fake SMTP server (From, To, Subject, body);
  - the test-message endpoint is approver-only and works.
- Frontend: the form sends the format, URL and secret per platform, and switches to
  recipients for email.

## Observed local results

- Deployed with `scripts/deploy.sh` (V34, mail starter).
- Creating an email subscription without SMTP was refused: "Email is not
  configured: set EAHP_SMTP_HOST (and sender) and redeploy".
- A DingTalk subscription with a deliberately invalid token and secret was created.
  No secret was returned. "Send test" reached the real `oapi.dingtalk.com` over
  TLS. DingTalk answered **HTTP 200** with `errcode 300005: token is not exist`,
  and EAHP recorded the attempt as failed (`error 300005: token is not exist`,
  2.4 s) rather than as delivered. The subscription was then deleted.
- With a real robot token or SMTP server, the same path delivers. No credentials
  were configured for this check.
