# P43: Auto-fix failing tests (方案 4 step 2)

Status: 方案 4 workflow built on the generic code agent (P35, P37). A failed QA run
can be handed to Claude Code. The agent changes the code in its sandbox, and EAHP
pushes a branch and opens a **draft** pull request. EAHP then **verifies the fix
itself**: it runs the same suite at the pull request's commit, compares the result
with the failed run, and reports the verdict on the pull request. Nothing is ever
merged.

## Architecture Explanation

- **Request.**
  - Operators click "Fix with code agent" on a failed suite run, or call
    `POST /api/v1/qa/runs/{runId}/auto-fix`.
  - Approvers can let EAHP propose a fix for every eligible failed run
    (`PUT /api/v1/qa/admin/projects/{key}/auto-fix {enabled}`; the Quality page
    has a checkbox). It is off by default.
  - Either way, the code agent run **waits for an approver**, like every
    `CODE_AGENT_RUN`.
- **Eligibility.** A request is refused, with a reason, unless:
  - it is a suite run (`QA_RUN_SUITE` or `QA_RUN_PRIVILEGED_SUITE`) that ended
    `TESTS_FAILED` or `BUILD_FAILED`;
  - at least one failure is a code defect (assertion, unexpected exception,
    compilation, timeout or unknown), not environment, runner or dependency
    problems, which a code change would only hide;
  - the repository is registered for the code agent **with allow push**, since
    the fix is verified on a pull request.

  Each failed run gets at most one auto-fix (409 otherwise).
- **Instructions.** They are built from EAHP's own records:
  - the suite, its command and image, and where its reports go;
  - up to 20 failing tests with category and message. Messages are test output,
    quoted as data, with control characters and backticks removed;
  - rules: fix production code; do not delete, skip or weaken tests (change a
    test only when it is clearly wrong, and explain why); keep the change small;
    run the failing tests; explain cause and fix.

  The agent works on the failed run's branch when it still points at that commit,
  so the draft pull request targets that branch. Otherwise it works on the exact
  commit and the pull request targets the default branch.
- **Lifecycle** (`qa_auto_fixes`, V36), advanced every 20 seconds:

  | Status | Meaning |
  | --- | --- |
  | `CODING` | the code agent run (waiting for approval or working) |
  | `NO_CHANGE` | the agent made no change |
  | `CODE_FAILED` | the agent run failed, was rejected, or the change was not pushed |
  | `VERIFYING` | the same suite runs at the agent branch's commit (same tool and timeout as the failed run) |
  | `FIXED` | the suite passes at that commit |
  | `PARTLY_FIXED` | some failing tests pass now, but the suite still fails |
  | `NOT_FIXED` | nothing that failed passes now |
  | `VERIFY_FAILED` | the verification run was rejected, expired or cancelled |

  The verdict uses the P23 comparison: tests fixed, still failing and newly
  failing. It is posted as a **pull request comment** (with the write token) and
  emitted as `QA_AUTO_FIX_FIXED`, `QA_AUTO_FIX_PARTLY_FIXED` or
  `QA_AUTO_FIX_NOT_FIXED`, which webhooks and P41 chat and email notifications
  announce.
- **Loop guards.** Runs on `eahp/code-agent/*` branches, verification runs and
  runs at a verification commit are never fixed again. Automatic proposals are
  limited to 3 per project per day and only consider failures from the last hour.
- **Console.**
  - A failed suite run's page has a "Fix with the code agent" panel that follows
    the fix: status, code agent run, draft PR and verification run.
  - The Quality page lists recent auto-fixes, with the opt-in checkbox for
    approvers.

## Trade-off Analysis

- **EAHP verifies, not the agent.** The agent reports that it ran the tests, but
  the verdict that counts comes from EAHP's own suite run in the usual sandbox,
  recorded like any other QA result and comparable with the failed run.
- **Draft pull requests only.** A person reviews every fix. The comment gives the
  reviewer the evidence; it does not replace the review.
- **Refusing environment failures.** A code agent asked to "fix" a missing Docker
  daemon or an unreachable registry tends to skip or mock tests. Refusing is
  cheaper and safer.

## Production Considerations

- Needs `ANTHROPIC_API_KEY`, the code agent image, `EAHP_GITHUB_WRITE_TOKEN` (with
  Contents and Pull requests write), and the repository registered with allow push.
- Every fix costs one approved code agent run plus one suite run. Automatic
  proposals are rate-limited and still need approval.
- V36 adds `qa_auto_fixes` and `qa_auto_fix_settings`.

## Bilingual Interview Q&A

Q: How do you let an AI agent fix failing tests without trusting what it says?

中文：只把代码缺陷类失败交给代码智能体，指令由平台记录的失败信息生成，每次都要审批。智能体只能开草稿 PR。平台自己在 PR 的提交上重跑同一套测试，和失败那次对比（修复了几个、仍失败几个、新增失败几个），把结论写到 PR 上。合并永远由人决定。

English: Only code-defect failures go to the agent. Its instructions come from the
platform's own records of the failures, and every run needs approval. The agent can
only open a draft PR. The platform reruns the same suite on the PR's commit,
compares it with the failed run (tests fixed, still failing, newly failing) and
posts the verdict on the PR. A person always decides whether to merge.

## Verification

- `QaAutoFixApiTest` (4), with a fake git, docker and GitHub and the real worker:
  - a failing suite goes from fix request (operator), to approval, to the agent
    with the expected instructions (failing test and message, rules, suite
    command), to the draft PR, to verification at the branch commit, to `FIXED`
    with a PR comment and an audit event;
  - the verification run cannot itself be fixed;
  - a fix that leaves the test failing is `NOT_FIXED`;
  - a missing repository registration, no push permission, environment-only
    failures and passing runs are refused with reasons;
  - automatic proposals are off by default, approver-only to enable, wait for
    approval, and come one per failed run.
- `CodeAgentApiTest` and `ChatMessagesTest` pass. Frontend: requesting a fix from
  a failed run's page and following it.

## Observed local results

A realistic regression was pushed to online-shop branch
`eahp-demo/sku-merge-regression` (never main): the SKU merge key lost its
`trim()`.

1. `frontend-unit` at `050367a6` ended **TESTS_FAILED, 2 of 66**: the
   `mergeItemsBySku` model test and the order API client contract test, both
   `ASSERTION`.
2. "Fix with code agent", then approval. Claude Code took 12 turns, 64 s and
   US$0.08. It restored `raw.sku.trim()` in `orders.ts` (one line, no test
   changes) and explained that the API client test failed through the same
   function. Egress: 18 connections, all allowed: `api.anthropic.com` and
   `registry.npmjs.org` (for `npm ci`).
3. EAHP pushed `eahp/code-agent/217dd9fc` and opened draft PR
   exedeehuashan/online-shop#63 against the demo branch.
4. EAHP ran `frontend-unit` at `4c719141`: **PASSED**, 2 tests fixed, 0 still
   failing, 0 new failures. The status is **FIXED**, and the verdict table was
   posted on PR #63, which stays a draft.
