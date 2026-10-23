# P27: Named API tokens

Status: generic EAHP capability. Besides the three deployment tokens (admin,
operator, viewer), approvers can issue a token per person or integration, so the
audit trail names who did what. Approvers can also revoke one token without
redeploying.

## Architecture Explanation

- `api_tokens` (V22) stores only a SHA-256 hash of each token. Each row also holds:
  - the name, description and roles;
  - creator and creation time;
  - optional expiry, last use, and revocation time and actor.

  A partial unique index allows one active token per name.
- **Format.** Tokens are `eahp_` + 32 random bytes in base64url (48 characters). A
  token is returned once, when issued; listings never include the token or its
  hash.
- **Roles.** VIEWER is always included; OPERATOR and APPROVER are added as chosen.
- **Reserved names.** Deployment tokens and EAHP's own actors (`admin`, `operator`,
  `viewer`, `system`, `scheduler`, `github`, `qa`, `worker`, `retention`, `planner`,
  `expiry`) cannot be issued.
- **Authentication.** The bearer filter first compares the token's hash with the
  configured deployment tokens in constant time, then looks the hash up among
  active (unrevoked, unexpired) named tokens. The principal is the token's name,
  so audit events, approvals, quarantines and admin events record it. Last use is
  updated at most once a minute.
- **API** (approver-only, under `/api/v1/admin`):
  - `GET /api/v1/admin/tokens`;
  - `POST /api/v1/admin/tokens` with `{name, description?, roles, expiresInDays?}`;
  - `POST /api/v1/admin/tokens/{id}/revoke`.

  Revocation takes effect on the next request.
- **Console.** The Test projects page has an API tokens section for approvers:
  issue (the token is shown once), see roles, last use and expiry, and revoke.

## Trade-off Analysis

Hashing with plain SHA-256 rather than a slow password hash is appropriate here,
because the tokens are 256-bit random values rather than passwords. It keeps the
per-request lookup cheap and indexable.

This is token-based identity, not SSO. An OIDC provider can later map users to the
same roles; named tokens remain useful for integrations either way.

## Production Considerations

- Prefer an expiry for every token; issue integration tokens with the least role
  they need.
- Keep the deployment admin token for break-glass use, and use named tokens day to
  day.
- V22 adds `api_tokens`.

## Bilingual Interview Q&A

Q: Why store only a hash of API tokens?

中文：数据库或备份泄露时，攻击者拿到的只是哈希，无法直接调用 API；令牌只在签发时显示一次。令牌本身是 256 位随机数，所以用 SHA-256 足够，不需要慢哈希。

English: If the database or a backup leaks, an attacker gets only hashes, which
cannot call the API; the token is shown once, when issued. Tokens are 256-bit
random values, so SHA-256 is sufficient without a slow hash.

## Verification

- `ApiTokenApiTest`:
  - only approvers issue tokens;
  - reserved or invalid names, unknown roles, bad expiry and unknown fields are
    rejected;
  - the token format is checked, and roles include VIEWER;
  - a duplicate active name gets 409;
  - an issued token is identified as `alice` with its roles, can create a task, and
    gets 403 on admin endpoints;
  - listings show last use and never the token or hash;
  - after revocation the token gets 401 and the name is free again;
  - a viewer token cannot write, and expired or unknown tokens get 401.
- `ApprovalSecurityApiTest` (31 tests) is unchanged.
- Frontend: 22 tests, including issuing a token and showing it once.

## Observed local results

Deployed with `scripts/deploy.sh` (V22), then:

1. Issued `p27-check` with OPERATOR.
2. `/me` returned `p27-check` with roles OPERATOR and VIEWER.
3. The admin listing returned 403 for that token.
4. After revocation, the token received 401.
