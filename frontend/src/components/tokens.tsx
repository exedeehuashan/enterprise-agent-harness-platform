import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useAction, usePolling } from '../hooks';
import { Empty, ErrorBanner, Section, Time } from './ui';
import { useT } from '../i18n';

/** Named API tokens for people and integrations; issuing one is on its own page, where the token is shown once. */
export function TokensPanel() {
  const tr = useT();
  const tokens = usePolling(() => api.tokens(), [], 0);
  const action = useAction();
  const revoke = async (id: string, name: string) => {
    if (window.confirm(`Revoke the token for ${name}? It stops working immediately.`) && await action.run(() => api.revokeToken(id))) void tokens.reload();
  };
  return (
    <Section title={tr('API tokens')} actions={<Link className="button secondary" to="/admin/tokens/new">{tr('Issue token')}</Link>}>
      <p className="muted small">Each person or integration gets its own token, so the audit trail names who did what. Every token can read; add roles as needed.</p>
      <ErrorBanner error={tokens.error ?? action.error} />
      {tokens.data?.length === 0 && <Empty>{tr('No named tokens.')}</Empty>}
      {tokens.data && tokens.data.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>{tr('Name')}</th><th>{tr('Roles')}</th><th>{tr('Last used')}</th><th>{tr('Expires')}</th><th /></tr></thead>
          <tbody>{tokens.data.map(t => (
            <tr key={t.id}>
              <td><strong>{t.name}</strong>{t.description && <div className="muted small">{t.description}</div>}</td>
              <td className="small">{t.roles.join(', ')}</td>
              <td className="small">{t.lastUsedAt ? <Time value={t.lastUsedAt} /> : 'never'}</td>
              <td className="small">{t.revokedAt ? <span className="badge badge-muted">{tr('revoked')}</span> : !t.active ? <span className="badge badge-muted">{tr('expired')}</span> : t.expiresAt ? <Time value={t.expiresAt} /> : 'never'}</td>
              {/* Expired tokens still hold their name until revoked, so they can be revoked too. */}
              <td>{!t.revokedAt && <button className="danger" disabled={action.busy} onClick={() => void revoke(t.id, t.name)}>{tr('Revoke')}</button>}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
    </Section>
  );
}
