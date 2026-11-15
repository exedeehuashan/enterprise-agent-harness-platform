import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { sourceLink, useFeatureLabel } from '../components/aiCalls';
import { Empty, ErrorBanner, Pager, Section, Time } from '../components/ui';
import { usePolling } from '../hooks';
import { useT } from '../i18n';

const STATUSES = [['ok', 'Succeeded'], ['failed', 'Failed'], ['running', 'In progress']] as const;

/** The AI call log: every call EAHP made to the model, newest first, filtered in the URL. */
export function AiCallsPage() {
  const tr = useT();
  const featureLabel = useFeatureLabel();
  const [params, setParams] = useSearchParams();
  const feature = params.get('feature') ?? '';
  const status = params.get('status') ?? '';
  const sourceType = params.get('sourceType') ?? '';
  const sourceId = params.get('sourceId') ?? '';
  const page = Number(params.get('page') ?? 0) || 0;
  const features = usePolling(() => api.aiCallFeatures(), [], 0);
  const calls = usePolling(() => api.aiCalls({ feature, status, sourceType, sourceId, page }), [feature, status, sourceType, sourceId, page], 0);
  const set = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(changes)) { if (v) next.set(k, v); else next.delete(k); }
    if (!('page' in changes)) next.delete('page');
    setParams(next);
  };
  const scoped = sourceLink(sourceType, sourceId);

  return (
    <>
      <p><Link to="/admin">← {tr('Admin')}</Link></p>
      <Section title={tr('AI calls')} actions={<>
        <label className="inline">{tr('Feature')}
          <select value={feature} onChange={e => set({ feature: e.target.value })}>
            <option value="">{tr('All features')}</option>
            {features.data?.map(f => <option key={f} value={f}>{featureLabel(f)}</option>)}
          </select>
        </label>
        <label className="inline">{tr('Result')}
          <select value={status} onChange={e => set({ status: e.target.value })}>
            <option value="">{tr('All results')}</option>
            {STATUSES.map(([v, label]) => <option key={v} value={v}>{tr(label)}</option>)}
          </select>
        </label>
      </>}>
        <p className="muted small">{tr('Every call EAHP makes to the AI model: what it was for, who started it, the tokens and time it took, and the prompt and answer with secrets redacted. The code agent runs in its own container; its output is on its run page.')}</p>
        {sourceType && <p className="small">{tr('Showing the calls for')} {scoped ? <Link to={scoped.to}>{tr(scoped.label)}</Link> : sourceType} <code className="wrap">{sourceId}</code> · <Link to="/admin/ai-calls">{tr('Show all')}</Link></p>}
        <ErrorBanner error={calls.error ?? features.error} />
        {calls.data && calls.data.items.length === 0 && <Empty>{tr('No AI calls yet.')}</Empty>}
        {calls.data && calls.data.items.length > 0 && (
          <div className="table-wrap"><table className="ai-calls">
            <thead><tr><th>{tr('Time')}</th><th>{tr('Feature')}</th><th>{tr('For')}</th><th>{tr('Result')}</th><th>{tr('Tokens')}</th><th>{tr('Duration')}</th><th>{tr('By')}</th></tr></thead>
            <tbody>
              {calls.data.items.map(c => {
                const src = sourceLink(c.sourceType, c.sourceId);
                return (
                  <tr key={c.id}>
                    <td><Link to={`/admin/ai-calls/${c.id}`}><Time value={c.createdAt} /></Link></td>
                    <td>{featureLabel(c.feature)}<div className="muted small">{c.model}</div></td>
                    <td>{src ? <Link to={src.to}>{tr(src.label)}</Link> : <span className="muted">—</span>}</td>
                    <td><CallStatus status={c.status} error={c.error} /></td>
                    <td>{tr('{in} in · {out} out', { in: c.inputTokens.toLocaleString(), out: c.outputTokens.toLocaleString() })}</td>
                    <td>{c.durationMs == null ? '—' : `${(c.durationMs / 1000).toFixed(1)} s`}</td>
                    <td>{c.actor}</td>
                  </tr>
                );
              })}
            </tbody>
          </table></div>
        )}
        {calls.data && <Pager page={page} totalPages={calls.data.totalPages} onPage={p => set({ page: String(p) })} />}
      </Section>
    </>
  );
}

export function CallStatus({ status, error }: { status: string; error: string | null }) {
  const tr = useT();
  if (status === 'ok') return <span className="badge badge-ok">{tr('Succeeded')}</span>;
  if (status === 'running') return <span className="badge">{tr('In progress')}</span>;
  return <span className="badge badge-bad" title={error ?? undefined}>{error ?? tr('Failed')}</span>;
}
