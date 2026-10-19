# P3: Identity, tool policy, human approval and audit

## Architecture Explanation

Spring Security authenticates random bearer API tokens and assigns fixed local
identities: admin (VIEWER/OPERATOR/APPROVER), operator (VIEWER/OPERATOR) and viewer
(VIEWER). Tokens are compared using SHA-256 and constant-time equality; there is
no default credential, cookie session, Basic auth or query-string credential.
The admin token is mandatory; optional identities require distinct valid tokens.
Only authorization headers are accepted, and repeated headers are rejected.
All API endpoints require authentication. Approvals/audit require APPROVER;
ordinary reads require VIEWER; writes require OPERATOR. ApprovalService.decide
also checks APPROVER at the service boundary, so an unauthenticated Worker
cannot invoke it directly.

Tool definitions are seeded by V4. DEMO_ANALYZE is a pure automatic tool;
DEMO_REVIEW runs the same pure calculation but requires approval and disallows
recovery retries. The code owns the supported tool/version/target/policy
contracts; the database may disable them but cannot weaken the gate or add
executable tools. There is no public policy modification or tool-registration
endpoint. Tool selection and timeout arguments remain strictly validated.

A gated start persists WAITING_APPROVAL, one step, immutable input snapshots,
tool version/target and a PENDING approval. The SHA-256 digest binds run/task IDs,
tool/version/target, title/description (including null distinction), and timeout.
Approvals expire in 30 minutes. The approval-wait deadline is separate from the
execution deadline, which starts at approval and still includes queue time.
Only one WAITING_APPROVAL/QUEUED/RUNNING run is allowed per task.

The approver supplies APPROVE/REJECT, the exact digest and a bounded reason code.
The task, run and approval are locked in that order; duplicate decisions cannot
both succeed. The Worker claims QUEUED work, then performs a separate immediate
pre-dispatch check: current lease, cancellation/deadline, supported policy,
input digest, approval status and expiry. It atomically consumes approval and
creates a STARTED invocation before calling the fixed tool. Duplicate dispatch
for the same claim is rejected. Completion updates invocation, step, run and
task summary in one transaction. Invocation IDs and digests are visible as
evidence; claim/approval-consumption tokens are internal.

## Trade-off Analysis

Static local tokens are sufficient to exercise the permission boundary without
an external identity provider. They do not provide individual enterprise users,
SSO, MFA, per-task ownership or two-person approval; admin can submit and approve.
Rotation currently requires replacing the environment value and restarting.
Future deployment beyond localhost requires TLS and an identity design review.

The approval-enabled tool remains deliberately side-effect-free. This verifies
human gating without introducing real order writes or deployments. The
application and Worker still share a trusted JVM and environment: method-level
roles are a logical boundary, not isolation against malicious in-process code.
Untrusted generated tests must later run in separate restricted execution
infrastructure without API/approval credentials.

## Production Considerations

- A digest identifies reviewed arguments; it does not authenticate the approver.
  Roles do that separately. Task edits do not alter already captured snapshots.
- Revalidation occurs in the transaction immediately before dispatch. It is not
  atomic with an external service call; expiry/cancellation or target changes
  after that point cannot be treated as a guaranteed stop. Both current tools
  are pure; future side effects need preconditions and idempotency.
- A controlled tool's lost claim becomes NEEDS_REVIEW, with UNKNOWN invocation
  outcome if dispatch had begun. Consumed approval is not automatically reused.
  A fresh run requires a fresh approval. Do not interpret a new run as resolving
  uncertain effects in a future external system.
- Run creation, approval request/decision/consumption, claim, dispatch, recovery,
  cancellation and terminal outcomes have database audit events. Actor comes
  from authenticated context or the trusted Worker, never a request-body actor.
  Audit includes IDs, action and bounded codes, not raw inputs, tokens or free
  text reasons. Authentication denials are enforced but are not database audit
  events in this version.
- Audit is append-only through the application API. A database administrator can
  still alter it; there is no cryptographic integrity or external audit sink.
- No authentication rate limit or automatic credential rotation is claimed.
  Viewer sees ordinary workspace task/run data; this is not tenant isolation.
- V4 adds tool definitions, approval requests, invocation records and audit
  events, with old runs retaining tool version 1/local-demo. V1–V3 are unchanged.
  Back up the local database before migration. Rollback needs care: the P2 app
  does not enforce P3 approvals or authentication and must not be treated as a
  safe rollback while approved/queued controlled runs exist.

## Verification strategy

The prior functional suites omit servlet filters explicitly so their business
assertions remain focused; a separate integration suite runs the real production
security filter chain against temporary PostgreSQL with random test credentials.
There is no production security-disable property. Existing manual claim/finish
fixtures now call pre-dispatch authorization, preserving execution invariants.
Configuration tests assert fail-closed behavior for absent, malformed and reused
credentials. Schema regression checks V3 run/task snapshot preservation in V4.

Security tests cover 401/403, role separation, worker method authorization,
pending gates, approve/reject/expiry/cancel, mismatched and cross-run digests,
input/tool/policy changes, missing approvals, duplicate decisions/dispatch,
concurrent decisions, audit redaction and non-retry of consumed approval.

## Bilingual Interview Q&A

Q: Why must execution revalidate an approval?

中文：批准之后，权限策略、参数或有效期可能改变。执行前要验证同一运行与摘要，并原子记录审批消耗，防止复用旧批准。

English: Policy, arguments or validity can change after approval. Before
execution, verify the same run and digest and atomically consume the approval.

Q: Does an audit table make a system tamper-proof?

中文：不。应用可以限制写入接口，但数据库管理员仍可修改记录。更强的保证需要独立审计存储、访问分离或完整性校验。

English: No. Application APIs can restrict mutation, but database administrators
can still change rows. Stronger guarantees need an independent audit store,
separated privileges or integrity verification.

## Hands-on Verification

1. Anonymous GET should return 401; viewer POST should return 403.
2. Operator starts DEMO_REVIEW: the run stays WAITING_APPROVAL with no invocation.
3. Admin reads approval and run snapshots, then approves their exact digest.
4. Worker completes the run; approval becomes CONSUMED with one invocation.
5. Repeating the decision returns 409; audit links operator/admin/worker actions.
6. A separate run may be rejected or cancelled. Use integration tests for
   deterministic expiry and post-approval mutation scenarios.

All curl examples in README require a private authorization header file.
Never commit runtime token/header files or local database dumps.

## Observed local results

Maven verify: 137 tests passed with zero failures/errors/skips (102 prior cases,
31 approval/security integration cases and four security-configuration cases).
The local deployment backed up data and the old JAR, applied V4 and retained
prior tasks. Real HTTP checks confirmed 401 anonymous, 403 viewer writes and
operator decisions, no invocation while waiting, one successful dispatch after
admin approval, CONSUMED approval, 409 on duplicate decision, REJECTED and
CANCELLED outcomes, and linked operator/admin/worker audit events. Approval
records persisted unchanged after an authenticated backend restart. Runtime
credentials and database dumps remain outside the repository.
