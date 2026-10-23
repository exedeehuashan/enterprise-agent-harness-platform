import { api } from '../api/client';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { ErrorBanner, Section } from './ui';

/** Domain modules plugged into this EAHP (core, 方案 4, …) and the tools each contributes; approvers can switch a domain off. */
export function ModulesPanel() {
  const { can } = useSession();
  const modules = usePolling(() => api.modules(), [], 0);
  const action = useAction();
  const toggle = async (key: string, enabled: boolean) => {
    if ((enabled || window.confirm(`Switch off ${key}? Its tools stop accepting runs and its background jobs pause.`)) && await action.run(() => api.moduleEnabled(key, enabled))) void modules.reload();
  };
  return (
    <Section title="Domain modules">
      <ErrorBanner error={modules.error ?? action.error} />
      {modules.data?.map(m => (
        <article key={m.key} className="item row spread">
          <div>
            <strong>{m.name}</strong> <code className="muted small">{m.key}</code>{' '}
            <span className={`badge ${m.enabled ? 'badge-ok' : 'badge-muted'}`}>{m.enabled ? 'enabled' : 'off'}</span>
            <p className="muted small">{m.description}</p>
            <div className="small">{m.tools.map(t => <code key={t.name} className={t.enabled ? '' : 'muted'}>{t.name} </code>)}</div>
          </div>
          {can('APPROVER') && m.canDisable && <button className="secondary" disabled={action.busy} onClick={() => void toggle(m.key, !m.enabled)}>{m.enabled ? 'Switch off' : 'Switch on'}</button>}
        </article>
      ))}
    </Section>
  );
}
