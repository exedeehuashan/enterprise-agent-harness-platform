# P40: Flaky-test quarantine suggestions

Status: 方案 4. P21 let approvers quarantine a failing test by hand. The dashboard
already listed flaky tests, but someone had to notice and act. EAHP now detects
flaky tests from evidence and proposes the quarantine. An approver confirms it in
one click or dismisses it. EAHP never quarantines on its own.

## Architecture Explanation

- **Detection** (`QaQuarantineSuggestions.detect`) runs every 10 minutes
  (`eahp.qa.flaky.interval-ms`). A test is flaky when it **both passed and failed
  at the same commit**, the same code with different results. Retries or reruns
  produce exactly this signal. The test is proposed when this happened on at least
  `eahp.qa.flaky.min-commits` (2) commits within `eahp.qa.flaky.days` (14) days.
  A test that only fails is broken, not flaky, and is not proposed.
- **Suggestions (V33).** `qa_quarantine_suggestions` stores the project, suite,
  test and number of flaky commits. Its evidence is up to 10 commits with pass
  and fail counts, plus the last failure message. The status is `PENDING`,
  `ACCEPTED`, `DISMISSED` or `RESOLVED`.
  - At most one pending suggestion exists per test (partial unique index).
  - A test that is already quarantined is not proposed. A pending suggestion
    whose test was quarantined another way becomes `RESOLVED`.
  - A decision (accept or dismiss) within the window snoozes the test, so a
    dismissed test is not proposed again immediately.
- **Events.** Each new suggestion writes an audit event
  `QA_QUARANTINE_SUGGESTED` (code = suite, run = the latest failing run), so
  webhooks and, later, notifications can announce it.
- **Decisions** (approvers only):
  - `POST /api/v1/qa/admin/quarantine-suggestions/{id}/accept` `{days?, reason?}`
    creates an ordinary P21 quarantine: 14 days by default, at most 30,
    expiring, audited as `QUARANTINE_CREATED`. Its reason cites the evidence:
    *"Flaky: passed and failed at the same commit on 2 commits in 14 days (EAHP
    suggestion 1a2b3c4d)."*
  - `POST …/dismiss` `{note?}` records who dismissed it and why, audited as
    `QUARANTINE_SUGGESTION_DISMISSED`.
  - Deciding twice gives 409.
- **Reading.** `GET /api/v1/qa/quarantine-suggestions?project=&status=` (viewer).
- **Console.** The Quality page's quarantine panel has a "Suggested by EAHP" list.
  Each entry shows the test, the commits where it passed and failed (with counts
  on hover) and the last failure. Approvers get "Quarantine 14 days" and
  "Dismiss" (with an optional reason).

## Trade-off Analysis

- **Same-commit pass and fail as the only signal.** Comparing across commits
  confuses real regressions and fixes with flakiness. The same code giving
  different results cannot be explained by the change.
- **Suggest, never act.** A quarantine weakens the gate, so a person stays
  accountable. The single click keeps that cheap.
- **Snooze on decision.** Without it, a dismissed test would be proposed again
  every 10 minutes.

## Production Considerations

- V33 adds `qa_quarantine_suggestions`. Detection is one aggregate query over the
  window of `qa_test_results` (indexed by project and time).
- Tune `EAHP_QA_FLAKY_MIN_COMMITS` and `EAHP_QA_FLAKY_DAYS` for noisy suites.

## Bilingual Interview Q&A

Q: How do you handle flaky tests without letting them silently weaken the release gate?

中文：只把"同一提交上既通过又失败"当作不稳定的证据，在 14 天内出现 2 次以上就生成隔离建议，附上提交和失败信息。由审批人一键确认（生成有期限、可审计的隔离）或驳回（窗口内不再提醒）。系统从不自动隔离。

English: Only "passed and failed at the same commit" counts as evidence of
flakiness. Twice within 14 days produces a quarantine suggestion with the commits
and the failure message. An approver confirms it in one click, which creates an
expiring, audited quarantine, or dismisses it, which snoozes the test for the
window. EAHP never quarantines on its own.

## Verification

- `QaSuiteToolApiTest.flakyTestsAreSuggestedForQuarantineAndApproversConfirmOrDismiss`:
  - a test that passed and failed at two commits is proposed once, with its
    evidence and an audit event;
  - a test that only fails is not proposed;
  - operators cannot accept, and invalid days are rejected;
  - accepting creates a 14-day quarantine with an evidence-citing reason, the gate
    passes, and accepting again gives 409;
  - after acceptance nothing new is proposed;
  - a dismissal with a note is audited and snoozes the test;
  - an invalid status filter gives 400.
- Frontend: suggestions show their evidence, and approvers confirm or dismiss
  (with a note) in one click.

## Observed local results

- Deployed with `scripts/deploy.sh` (V33). online-shop has no test that passed and
  failed at the same commit in the last 14 days. A direct query of
  `qa_test_results` agrees, so the detector correctly proposes nothing, and
  `GET /api/v1/qa/quarantine-suggestions?project=online-shop` returns `[]`.
