# P1: Task editing with optimistic concurrency

## Architecture Explanation

Task PATCH validates a bounded set of fields and requires a version supplied as
If-Match. The service checks the current version, merges omitted fields from
the stored task, validates the resulting title/description, and flushes inside
one transaction. Hibernate's @Version adds the version to the SQL update
predicate, protecting against races after the application-level comparison.
Creation and detail responses expose the version as both JSON and ETag.

## Trade-off Analysis

A version column gives a precise concurrency token without relying on timestamp
resolution. PATCH distinguishes an omitted description from explicit null.
This API deliberately accepts one quoted numeric ETag, not the full range of
HTTP If-Match expressions. A no-op preserves version and updatedAt.
Adding version to JSON is additive; strict consumers must allow the new field.

## Production Considerations

V2 adds a non-null version column with default 0, preserving existing rows and
leaving V1 untouched. The local deployment was backed up before migration.
Large deployments need a migration lock-duration assessment. On 412, clients
must reload and reconcile changes rather than blindly retry with a new version.
Optimistic locking prevents lost database updates; it is not authorization or
an execution lock for external side effects. Execution remains outside this PR.
A database backup is sensitive and stays outside Git. Reverting to an older
application does not remove V2; restoring data requires an explicit recovery plan.

## Bilingual Interview Q&A

Q: Why compare versions in both Java and SQL?

中文：Java 检查尽早返回过期版本错误；带版本条件的 SQL 更新防止检查后发生的并发覆盖。

English: The Java check detects an already stale request early. The versioned
SQL update closes the race between that check and the actual write.

Q: What should a client do after a 412 response?

中文：重新读取最新任务，比较并协调修改，然后提交新的条件请求。

English: Reload the current task, reconcile the changes, and submit a new
conditional request.

## Hands-on Verification

1. Create a task or GET its detail and retain its ETag.
2. PATCH title with that ETag; expect 200 and an incremented version.
3. PATCH with the old ETag; expect 412 and no overwrite.
4. Omit If-Match; expect 428. Try changing status; expect 400.
5. PATCH description to null with the current ETag; expect it to be cleared.

Validation: 72 tests passed locally (15 original, 31 listing, 26 editing),
including a coordinated two-transaction race with exactly one successful writer
and a V1-to-V2 migration preserving existing data. Local API smoke checks passed
for creation, edit, stale/missing version, protected fields, detail and list.
A dedicated demonstration task was created for these checks; prior tasks were
not edited. GitHub CI provides separate verification after publication.
