# P16: QA registry administration

Status: projects can be onboarded and governed without database access. Approvers
manage projects and their policy, registry suites, pull request triggers and
release gates through an API and the console; every change is audited.

## Architecture Explanation

- `QaAdminController` under `/api/v1/qa/admin/**`, protected twice: the URL rule
  requires APPROVER, and every `QaAdminService` method has
  `@PreAuthorize("hasRole('APPROVER')")`, so in-process callers cannot bypass it.
- Endpoints:
  - `POST /projects` (201), `GET /projects/{key}`, `PATCH /projects/{key}`;
  - `PUT /projects/{key}/suites/{suite}` (create or replace; disable with
    `"enabled": false`);
  - `PUT /projects/{key}/triggers/{suite}` `{"enabled": bool}`;
  - `PUT /projects/{key}/gate` `{"requiredSuites": [...]}`, `DELETE /projects/{key}/gate`;
  - `GET /events?project=` (change history).
- **Validation**: strict JSON (unknown fields rejected), key patterns, https
  repository URLs without `..`, relative suites file path, image `name:tag`,
  timeouts 30–3600. Registry suites are validated by the same parser as
  repository suites files (P12), and must satisfy the project's policy. A policy
  change that would put an enabled registry suite outside the policy is rejected.
- **Audit**: `qa_admin_events` records actor, action, project, subject and the
  before/after state of each change. Calls that change nothing leave no event.
- **Console**: the Test projects page shows, for approvers only, a "Register a
  project" form and, per project, an "Administer" panel: policy editor, trigger
  toggles, gate editor, registry suite editor (argv one per line, no shell) and
  the change history.

## Trade-off Analysis

Admin actions take effect immediately rather than through an approval workflow of
their own. That matches the current single-administrator setup; with several
administrators, two-person review of policy changes (similar to P3's run
approvals) would be the next step.

A separate admin audit table was chosen over the run audit table (P3), which is
keyed by run. Registry changes have no run, and before/after state is the useful
evidence for them.

## Production Considerations

- New projects default to no Docker access, network egress allowed, a 30-minute
  limit and the Maven image only. Docker access should stay off unless a project
  needs Testcontainers.
- Registering a repository means its code will run in test containers; only
  register repositories whose contributors are trusted.
- V15 adds `qa_admin_events`.

## Bilingual Interview Q&A

Q: Why validate registry suites with the same parser as repository files?

中文：两种来源最终都执行同样的命令。用同一套规则，管理员配置和仓库声明的约束完全一致，不会出现一边宽一边严的漏洞。

English: Both sources end up executing commands. One rule set means the
administrator path and the repository path are equally strict; neither can be
the weaker way in.

Q: Why record before and after state for admin changes?

中文：出问题时需要知道是谁、在什么时候、把什么改成了什么，才能判断影响并恢复。只记录动作名称不够。

English: When something goes wrong you need who changed what, when, and from
what to what, to judge the impact and restore it. An action name alone is not
enough.

## Verification

- `QaAdminApiTest` (3 tests): onboarding a project with policy, suite, trigger
  and gate, the project appearing in the normal catalog, ordered audit events with
  before/after and no event for a no-op; approver-only access (403 for operator
  and viewer, 401 anonymous); eight invalid project inputs, disallowed images and
  Docker access, unsafe report directories, bad keys, policy changes that would
  break existing suites, empty gates, wrong types, 404 and limit validation.
- Frontend: 19 tests, including registering a project and toggling a trigger as an
  approver, and the forms being hidden from operators. Related backend suites:
  108 pass.

## Observed local results

Deployed with `scripts/deploy.sh`. On the live deployment the admin view of
online-shop showed its triggers and gate; operator and viewer requests returned
403; setting an existing trigger recorded an audit event with before and after
state (this no-op event led to the rule that unchanged calls are not recorded).
