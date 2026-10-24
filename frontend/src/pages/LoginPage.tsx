import { useEffect, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import { ErrorBanner } from '../components/ui';
import { useAction } from '../hooks';
import { useSession } from '../session';
import { LanguageSwitch, useT } from '../i18n';
import { startSsoLogin, type OidcConfig } from '../sso';

export function LoginPage() {
  const tr = useT();
  const { signIn } = useSession();
  const [token, setToken] = useState('');
  const [sso, setSso] = useState<OidcConfig | null>(null);
  const action = useAction();
  useEffect(() => { api.authConfig().then(c => setSso(c.oidc)).catch(() => setSso(null)); }, []);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    void action.run(() => signIn(token.trim()));
  };

  return (
    <main className="login">
      <form className="card" onSubmit={submit}>
        <div className="row spread"><h1>{tr('EAHP Console')}</h1><LanguageSwitch /></div>
        {sso && (
          <>
            <button type="button" disabled={action.busy} onClick={() => void action.run(() => startSsoLogin(sso))}>{tr('Sign in with SSO')}</button>
            <p className="muted small">{tr('Or use an API token:')}</p>
          </>
        )}
        <p className="muted">{tr('Sign in with your API token. It is kept only for this browser tab.')}</p>
        <label>
          {tr('API token')}
          <input type="password" autoComplete="off" value={token} onChange={e => setToken(e.target.value)} required minLength={32} />
        </label>
        <ErrorBanner error={action.error} />
        <button type="submit" disabled={action.busy || token.trim().length < 32}>{action.busy ? tr('Signing in…') : tr('Sign in')}</button>
      </form>
    </main>
  );
}

/** The identity provider returns here: exchange the code, sign in, continue to the console. */
export function SsoCallback() {
  const tr = useT();
  const { signIn } = useSession();
  const [error, setError] = useState<Error | null>(null);
  useEffect(() => {
    let live = true;
    import('../sso').then(m => m.completeSsoLogin(window.location.search))
      // The one-time code leaves the address bar (and history) before anything else happens.
      .then(token => { window.history.replaceState(null, '', '/'); return signIn(token); })
      .catch(e => { if (live) setError(e as Error); });
    return () => { live = false; };
    // Runs once per callback page load; the code can only be exchanged once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return (
    <main className="login">
      <div className="card">
        <h1>{tr('EAHP Console')}</h1>
        {error ? <><ErrorBanner error={error} /><a href="/">{tr('Back to sign-in')}</a></> : <p className="muted">{tr('Completing sign-in…')}</p>}
      </div>
    </main>
  );
}
