import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { ApiTokenIssued } from '../api/types';
import { ErrorBanner, FormPage } from '../components/ui';
import { useAction } from '../hooks';
import { useT } from '../i18n';

const ROLES = ['OPERATOR', 'APPROVER'] as const;

/** Issues a named API token; the token is shown here once and never again. */
export function NewTokenPage() {
  const tr = useT();
  const navigate = useNavigate();
  const action = useAction();
  const [form, setForm] = useState({ name: '', description: '', roles: [] as string[], days: 90 });
  const [issued, setIssued] = useState<ApiTokenIssued | null>(null);
  const issue = async (e: FormEvent) => {
    e.preventDefault();
    const result = await action.run(() => api.issueToken({ name: form.name.trim(), description: form.description.trim() || null, roles: form.roles, expiresInDays: form.days }));
    if (result) setIssued(result);
  };
  return (
    <FormPage back="/admin" backLabel={tr('Administration')} title={tr('Issue token')}>
      {issued ? (
        <div className="stack">
          <div className="notice" role="status">{tr('Token for')} <strong>{issued.token.name}</strong> {tr('(shown only now):')} <code>{issued.secret}</code></div>
          <p className="muted small">{tr('Copy it now and hand it over securely; it cannot be shown again.')}</p>
          <div><button onClick={() => navigate('/admin')}>{tr('Done')}</button></div>
        </div>
      ) : (
        <form className="stack" onSubmit={issue}>
          <p className="muted small">Each person or integration gets its own token, so the audit trail names who did what. Every token can read; add roles as needed.</p>
          <label>{tr('Name')}<input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} required placeholder="alice" pattern="[a-z0-9][a-z0-9._-]{1,39}" /></label>
          <label>{tr('Description')}<input value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} maxLength={200} /></label>
          <div className="row">{ROLES.map(r => (
            <label key={r} className="inline"><input type="checkbox" checked={form.roles.includes(r)}
              onChange={e => setForm({ ...form, roles: e.target.checked ? [...form.roles, r] : form.roles.filter(x => x !== r) })} />{r.toLowerCase()}</label>
          ))}</div>
          <label>{tr('Days')}<input type="number" min={1} max={3650} value={form.days} onChange={e => setForm({ ...form, days: Number(e.target.value) })} /></label>
          <ErrorBanner error={action.error} />
          <div><button type="submit" disabled={action.busy}>{tr('Issue token')}</button></div>
        </form>
      )}
    </FormPage>
  );
}
