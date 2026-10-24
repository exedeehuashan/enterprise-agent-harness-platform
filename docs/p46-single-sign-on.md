# P46: Single sign-on (OIDC)

Status: generic EAHP capability. Until now everyone signed in with API tokens: the
three deployment tokens or named tokens issued by approvers (P27). The console now
also supports the organisation's identity provider through **OpenID Connect**.
People sign in with their company account, and EAHP roles come from the provider's
role assignments. API tokens keep working for scripts and integrations.

## Architecture Explanation

- **Backend** (`OidcVerifier`, enabled by `eahp.security.oidc.issuer`):
  - A Bearer value shaped like a JWT goes to the verifier. Anything else is
    handled as an API token, exactly as before.
  - A token is accepted only if:
    - its signature checks against the provider's published keys (JWKS, cached
      and refreshed by Nimbus; unsigned tokens are rejected);
    - its `iss` equals the configured issuer;
    - its `aud` includes `eahp`;
    - it is within its validity period.
  - The keys can be fetched on an internal address (`EAHP_SSO_JWKS_URI`, for
    example the Keycloak container) while the issuer stays the public URL.
  - **Roles** come from a claim, by default Keycloak's `realm_access.roles`
    (`eahp.security.oidc.roles-claim`; `roles` or `groups` also work):
    `eahp-viewer`, `eahp-operator` and `eahp-approver` map to VIEWER, OPERATOR
    and APPROVER. A higher role includes the lower ones. A user with none of them
    is signed in but may do nothing (403).
  - The name is `sso:<preferred_username>`, which appears in audits and
    approvals.
  - `GET /api/v1/auth/config` (public) tells the console the issuer, the client
    id and the scope, and never a secret.
- **Console** (`sso.ts`): the Authorization Code flow with **PKCE** for a public
  client.
  1. "Sign in with SSO" loads the provider's discovery document and checks that
     its issuer matches (mix-up protection).
  2. It creates a verifier, an S256 challenge and a state, keeps them in this
     tab's sessionStorage, and goes to the provider's login page.
  3. On `/auth/callback` it checks the state, exchanges the code for an access
     token, **removes the code from the address bar first**, and signs in. The
     token is kept like an API token (sessionStorage only).
  4. When the token expires (one hour in the dev realm), the next call returns
     401 and the user signs in again; the provider's own session makes that
     quick.
  - The CSP `connect-src` allows exactly the provider's origin
    (`EAHP_SSO_ORIGIN`, rendered by nginx's template step; empty by default).
- **Local Keycloak for development:**
  - `scripts/sso-dev.sh` renders `sso/eahp-realm.template.json` into
    `sso/generated/` (mode 700/600, ignored by git). The realm has:
    - the public `eahp-console` client with PKCE S256;
    - redirect `http://127.0.0.1:8088/auth/callback`;
    - an audience mapper that adds `eahp`;
    - the three roles;
    - test users alice (approver), bob (operator) and carol (viewer), with
      random passwords listed in `sso/generated/dev-users.txt`.
  - `EAHP_SSO=on scripts/deploy.sh` adds `docker-compose.sso.yml`:
    - Keycloak 26.0.7 on `127.0.0.1:8180`, importing the realm, with
      `KC_HOSTNAME` as the issuer and a dynamic backchannel;
    - a health check;
    - the backend's issuer and JWKS settings;
    - the console's CSP origin.
- **A real identity provider:** set `EAHP_SSO_ISSUER`, optionally
  `EAHP_SSO_JWKS_URI`, and `EAHP_SSO_ORIGIN` in `.env`, then register the client
  as `.env.example` describes. Azure AD (Entra ID), Okta and others work the same
  way when they put the roles in a claim that `roles-claim` names.

## Trade-off Analysis

- **Bearer tokens in the browser instead of a backend session.** EAHP's API is
  stateless and token-based; reusing that keeps one code path for SSO, API
  tokens, scripts and tests. PKCE makes the public client safe without a secret,
  and short-lived tokens limit exposure.
- **Roles from the provider.** Who may approve is decided where the
  organisation manages people. EAHP only maps three role names, and anyone
  without one gets nothing.
- **No refresh tokens.** Each re-login is a redirect that the provider usually
  completes without a prompt. Refresh tokens in the browser would be another
  long-lived secret to protect.

## Production Considerations

- Use HTTPS for both the console and the provider outside localhost. Set the
  realm's `sslRequired` back to `external`.
- Keep the access token lifetime short (5–60 minutes).
- Keep `EAHP_SECURITY_ADMIN_TOKEN` as break-glass access, stored safely.

## Bilingual Interview Q&A

Q: How do you add SSO to a stateless, token-based API and an SPA?

中文：后端把 OIDC 访问令牌当作另一种 Bearer 令牌校验：用提供方公布的公钥验签，检查 issuer、audience 和有效期，再从角色声明映射成 EAHP 角色。前端是公共客户端，用授权码 + PKCE 流程：state 防 CSRF，校验 issuer 防混淆，回调后立刻把 code 从地址栏去掉。原有的 API 令牌照常可用。

English: The backend verifies OIDC access tokens as another kind of Bearer token:
the signature against the provider's published keys, then issuer, audience and
expiry, and roles mapped from a claim. The SPA is a public client using the
Authorization Code flow with PKCE: state against CSRF, an issuer check against
mix-up, and the code removed from the address bar right after the callback. API
tokens keep working.

## Verification

- `SsoApiTest` (3), with a real RSA key and a JWKS served locally:
  - the public sign-in settings;
  - an approver token maps to all three roles and can use the admin API;
  - an operator token is refused admin (403);
  - a user without EAHP roles gets 403;
  - static tokens still work;
  - another issuer, another audience, an expired token, another key and an
    unsigned token are each rejected (401).
- `ApiSecurityConfigurationTest` and `ApiTokenApiTest` pass unchanged.
- Frontend tests (30 in all):
  - the login page starts the flow with S256 PKCE, client id, redirect URI and
    state;
  - the callback exchanges the code with the verifier, signs in and clears the
    URL;
  - a forged state is rejected.

## Observed local results

- `scripts/sso-dev.sh` generated the realm and the test users.
  `EAHP_SSO=on scripts/deploy.sh` started Keycloak 26.0.7 and printed
  "SSO on".
- `GET /api/v1/auth/config` returned the issuer
  `http://127.0.0.1:8180/realms/eahp` and client `eahp-console`. The console's
  CSP allowed `connect-src 'self' http://127.0.0.1:8180`.
- In the browser, "使用单点登录" (Sign in with SSO) led to Keycloak's login page.
  The URL carried `code_challenge_method=S256` and the callback URL. Signing in
  as the test user alice returned to the console as **`sso:alice` with APPROVER ·
  OPERATOR · VIEWER**, at `/` with no code in the URL. The Approvals and
  Administration tabs were visible.
