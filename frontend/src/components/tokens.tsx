import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { ApiTokenIssued } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { Empty, ErrorBanner, Section, Time } from './ui';

const ROLES = ['OPERATOR', 'APPROVER'] as const;

/** Named API tokens for people and integrations; the token is shown once, when it is issued. */
export function TokensPanel() {
  const tokens = usePolling(() => api.tokens(), [], 0);
  const action = useAction();
  const [form, setForm] = useState({ name: '', description: '', roles: [] as string[], days: 90 });
  const [issued, setIssued] = useState<ApiTokenIssued | null>(null);
  const issue = async (e: FormEvent) => {
    e.preventDefault();
    const result = await action.run(() => api.issueToken({ name: form.name.trim(), description: form.description.trim() || null, roles: form.roles, expiresInDays: form.days }));
    if (result) { setIssued(result); setForm({ name: '', description: '', roles: [], days: 90 }); void tokens.reload(); }
  };
  const revoke = async (id: string, name: string) => {
    if (window.confirm(`Revoke the token for ${name}? It stops working immediately.`) && await action.run(() => api.revokeToken(id))) void tokens.reload();
  };
  return (
    <Section title="API tokens">
      <p className="muted small">Each person or integration gets its own token, so the audit trail names who did what. Every token can read; add roles as needed.</p>
      <ErrorBanner error={tokens.error ?? action.error} />
      {issued && <div className="notice" role="status">Token for <strong>{issued.token.name}</strong> (shown only now): <code>{issued.secret}</code>
        <button className="secondary" onClick={() => setIssued(null)}>Done</button></div>}
      {tokens.data?.length === 0 && <Empty>No named tokens.</Empty>}
      {tokens.data && tokens.data.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>Name</th><th>Roles</th><th>Last used</th><th>Expires</th><th /></tr></thead>
          <tbody>{tokens.data.map(t => (
            <tr key={t.id}>
              <td><strong>{t.name}</strong>{t.description && <div className="muted small">{t.description}</div>}</td>
              <td className="small">{t.roles.join(', ')}</td>
              <td className="small">{t.lastUsedAt ? <Time value={t.lastUsedAt} /> : 'never'}</td>
              <td className="small">{t.revokedAt ? <span className="badge badge-muted">revoked</span> : !t.active ? <span className="badge badge-muted">expired</span> : t.expiresAt ? <Time value={t.expiresAt} /> : 'never'}</td>
              {/* Expired tokens still hold their name until revoked, so they can be revoked too. */}
              <td>{!t.revokedAt && <button className="danger" disabled={action.busy} onClick={() => void revoke(t.id, t.name)}>Revoke</button>}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      <form className="row" onSubmit={issue}>
        <label className="inline">Name<input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} required placeholder="alice" pattern="[a-z0-9][a-z0-9._-]{1,39}" /></label>
        <label className="inline">Description<input value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} maxLength={200} /></label>
        {ROLES.map(r => (
          <label key={r} className="inline"><input type="checkbox" checked={form.roles.includes(r)}
            onChange={e => setForm({ ...form, roles: e.target.checked ? [...form.roles, r] : form.roles.filter(x => x !== r) })} />{r.toLowerCase()}</label>
        ))}
        <label className="inline">Days<input type="number" min={1} max={3650} value={form.days} onChange={e => setForm({ ...form, days: Number(e.target.value) })} /></label>
        <button type="submit" className="secondary" disabled={action.busy}>Issue token</button>
      </form>
    </Section>
  );
}
