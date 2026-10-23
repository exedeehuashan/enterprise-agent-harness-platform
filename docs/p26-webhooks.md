# P26: Webhook notifications

Status: generic EAHP capability. Other systems, such as a team chat bridge, CI or a
ticketing tool, learn about EAHP events as they happen: runs created, completed or
failed, approvals requested, QA results ingested.

## Architecture Explanation

- **Source.** `audit_events` is already EAHP's ordered event log:
  - run lifecycle events (`RUN_CREATED`, `RUN_CLAIMED`, `RUN_COMPLETED`,
    `RUN_FAILED`, …);
  - approvals (`APPROVAL_REQUESTED`, …);
  - plan steps;
  - `QA_RESULT_INGESTED`, which is new: recorded when a QA run's evidence is
    ingested, with the outcome as its code.
- **Cursor per subscription.** Each subscription (`webhook_subscriptions`, V21)
  keeps a cursor into the audit log and starts after the last event that existed
  when it was created. This gives in-order, at-least-once delivery, and a receiver
  that was down catches up from where it stopped.
- **Filtering.** Patterns are `*`, `PREFIX_*` or an exact action. Events that don't
  match only move the cursor forward.
- **Payload.** JSON containing:
  - the event's `id`, `action`, `actor`, `code` and `occurredAt`;
  - for run events, `run`: `id`, `taskId`, `tool`, `status`, `errorCode`, and for
    QA runs `project`, `suite` and `commit`.
- **Headers.** `X-EAHP-Event`, `X-EAHP-Delivery` (`subscription:eventId`, usable for
  de-duplication) and `X-EAHP-Signature: sha256=<hex HMAC-SHA256(secret, body)>`.
- **Failures.** A non-2xx response or a network error keeps the event:
  - it is retried with backoff of 10 s × 2ⁿ, capped at 1 h;
  - after 20 consecutive failures the subscription pauses;
  - resuming clears the failure count and retries the same event.

  Every attempt is logged in `webhook_deliveries`.
- **Security.**
  - Only approvers manage webhooks (`/api/v1/admin/webhooks`).
  - URLs must be http(s), without credentials or fragment.
  - Redirects are never followed, connections time out after 5 s and requests after
    10 s.
  - The 256-bit signing secret is returned only once, at creation; listings never
    include it.
- **Job.** `WebhookJob` runs every 5 s with the worker.
- **Console.** The Test projects page has a Webhooks section for approvers: add,
  pause, resume and delete webhooks, and see their health (last error, failures in
  a row, last delivery). The secret is shown once.

## Trade-off Analysis

Reading from the audit log rather than adding a separate outbox means nothing new
has to be written at every event site, and webhooks can never miss an audited
event. The cost is that only audited events can be delivered. The QA ingestion
event is the one addition made for this.

Ordered delivery means one failing event blocks the ones after it for that
subscription. That is the right trade for consumers that track state; backoff and
pausing bound the impact.

## Production Considerations

- Receivers should verify the signature and de-duplicate on `X-EAHP-Delivery`,
  since delivery is at least once.
- Webhook URLs are reached from the backend container. `host.docker.internal`
  reaches the host (P24).
- Secrets are stored in the database to sign requests; protect database backups
  accordingly.
- V21 adds `webhook_subscriptions` and `webhook_deliveries`.

## Bilingual Interview Q&A

Q: How do you guarantee webhook ordering and no lost events?

中文：每个订阅在审计日志里有自己的游标，只有接收方返回 2xx 才前移；失败就带退避重试同一条事件，连续失败 20 次暂停，恢复后从停下的地方继续。所以是按顺序、至少一次投递。

English: Each subscription has its own cursor into the audit log that only moves
on a 2xx response. A failure retries the same event with backoff; 20 failures in a
row pause the subscription, and resuming continues from where it stopped. Delivery
is in order and at least once.

Q: How does a receiver know a webhook really came from EAHP?

中文：每个请求都用创建时生成的密钥对正文做 HMAC-SHA256 签名，接收方用同一个密钥重新计算并比较；密钥只在创建时显示一次。

English: Each request carries an HMAC-SHA256 of the body, computed with the secret
generated at creation; the receiver recomputes and compares it. The secret is shown
only once.

## Verification

- `WebhookApiTest` runs against a real local receiver:
  - subscriptions are approver-only and validated (scheme, credentials, empty or
    lower-case patterns, unknown fields), and the secret is never listed;
  - events from before the subscription are skipped;
  - only matching events are delivered, in order, with valid signatures and the QA
    outcome as `code`;
  - a 500 keeps the event, schedules a retry and is not retried early; the 20th
    consecutive failure pauses the subscription;
  - resuming delivers the held event first; the delivery log shows both attempts;
    delete works.
- Frontend: 22 tests, including adding a webhook and showing its one-time secret.

## Observed local results

Deployed with `scripts/deploy.sh` (V21). I registered a webhook to a receiver on
the host (`http://host.docker.internal:18999/hook`) for `RUN_*` and
`QA_RESULT_INGESTED`, then ran online-shop's `catalog-api-smoke` against UAT. The
receiver got, in order:

1. `RUN_CREATED`
2. `RUN_CLAIMED`
3. `RUN_COMPLETED`
4. `QA_RESULT_INGESTED` (`PASSED`) for an earlier `frontend-unit` run
5. `QA_RESULT_INGESTED` (`PASSED`) for `catalog-api-smoke`

All signatures verified. The test webhook was then deleted.
