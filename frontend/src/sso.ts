/**
 * Single sign-on in the browser (P46): OpenID Connect Authorization Code flow with PKCE for a public client. The
 * console never holds a client secret; the one-time verifier and state live in this tab's sessionStorage only until
 * the callback, and the resulting access token is kept like an API token (sessionStorage, never in a URL). Signing
 * out also ends the provider's session (RP-initiated logout, sent as a form POST so the ID token stays out of URLs);
 * otherwise the next "Sign in with SSO" would sign the same user straight back in.
 */
export interface OidcConfig { issuer: string; clientId: string; scope: string }

const KEY = 'eahp.sso.pending';
const LOGOUT_KEY = 'eahp.sso.logout';

/** How the console leaves for the identity provider (replaced in tests, where the page cannot navigate). */
export const navigation = {
  assign: (url: string) => window.location.assign(url),
  post: (url: string, fields: Record<string, string>) => {
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = url;
    for (const [name, value] of Object.entries(fields)) {
      const input = document.createElement('input');
      input.type = 'hidden'; input.name = name; input.value = value;
      form.appendChild(input);
    }
    document.body.appendChild(form);
    form.submit();
  },
};

function base64url(bytes: Uint8Array) {
  let s = '';
  bytes.forEach(b => { s += String.fromCharCode(b); });
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function random(n = 32) { const b = new Uint8Array(n); crypto.getRandomValues(b); return base64url(b); }

export function redirectUri() { return `${window.location.origin}/auth/callback`; }

async function discover(issuer: string) {
  const res = await fetch(`${issuer.replace(/\/+$/, '')}/.well-known/openid-configuration`);
  if (!res.ok) throw new Error(`The identity provider did not answer (${res.status}).`);
  const d = await res.json() as { issuer: string; authorization_endpoint: string; token_endpoint: string; end_session_endpoint?: string };
  // Mix-up protection: the provider must be the issuer EAHP trusts.
  if (d.issuer !== issuer) throw new Error('The identity provider reported a different issuer.');
  return d;
}

/** Sends the browser to the identity provider's login page. */
export async function startSsoLogin(config: OidcConfig) {
  const d = await discover(config.issuer);
  const verifier = random(48), state = random(16);
  const challenge = base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
  sessionStorage.setItem(KEY, JSON.stringify({ verifier, state, tokenEndpoint: d.token_endpoint, endSession: d.end_session_endpoint, clientId: config.clientId }));
  const url = new URL(d.authorization_endpoint);
  url.search = new URLSearchParams({ response_type: 'code', client_id: config.clientId, redirect_uri: redirectUri(), scope: config.scope,
    state, code_challenge: challenge, code_challenge_method: 'S256' }).toString();
  navigation.assign(url.toString());
}

/** On the callback page: checks the state and exchanges the code for an access token. */
export async function completeSsoLogin(search: string): Promise<string> {
  const params = new URLSearchParams(search);
  const raw = sessionStorage.getItem(KEY);
  sessionStorage.removeItem(KEY);
  if (params.get('error')) throw new Error(`Sign-in was not completed: ${params.get('error_description') ?? params.get('error')}`);
  if (!raw) throw new Error('No sign-in was started in this tab. Start again from the login page.');
  const pending = JSON.parse(raw) as { verifier: string; state: string; tokenEndpoint: string; endSession?: string; clientId: string };
  if (!params.get('code') || params.get('state') !== pending.state) throw new Error('The sign-in response did not match the request. Start again.');
  const res = await fetch(pending.tokenEndpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'authorization_code', code: params.get('code')!, redirect_uri: redirectUri(),
      client_id: pending.clientId, code_verifier: pending.verifier }).toString(),
  });
  const body = await res.json().catch(() => ({})) as { access_token?: string; id_token?: string; error_description?: string };
  if (!res.ok || !body.access_token) throw new Error(`The identity provider refused the sign-in${body.error_description ? `: ${body.error_description}` : ''}.`);
  if (pending.endSession) {
    sessionStorage.setItem(LOGOUT_KEY, JSON.stringify({ endSession: pending.endSession, idToken: body.id_token, clientId: pending.clientId }));
  }
  return body.access_token;
}

/** Ends the provider's session when this tab signed in with SSO; returns whether the browser is leaving. */
export function endSsoSession(): boolean {
  let raw: string | null = null;
  try { raw = sessionStorage.getItem(LOGOUT_KEY); sessionStorage.removeItem(LOGOUT_KEY); } catch { /* storage unavailable */ }
  if (!raw) return false;
  const s = JSON.parse(raw) as { endSession: string; idToken?: string; clientId: string };
  const fields: Record<string, string> = { client_id: s.clientId, post_logout_redirect_uri: `${window.location.origin}/` };
  if (s.idToken) fields.id_token_hint = s.idToken;
  navigation.post(s.endSession, fields);
  return true;
}
