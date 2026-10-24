import { useState, type FormEvent } from 'react';
import { ErrorBanner } from '../components/ui';
import { useAction } from '../hooks';
import { useSession } from '../session';
import { LanguageSwitch, useT } from '../i18n';

export function LoginPage() {
  const tr = useT();
  const { signIn } = useSession();
  const [token, setToken] = useState('');
  const action = useAction();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    void action.run(() => signIn(token.trim()));
  };

  return (
    <main className="login">
      <form className="card" onSubmit={submit}>
        <div className="row spread"><h1>{tr('EAHP Console')}</h1><LanguageSwitch /></div>
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
