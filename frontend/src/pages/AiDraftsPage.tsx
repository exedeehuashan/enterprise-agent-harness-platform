import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { Empty, ErrorBanner, Pager, Section, Time } from '../components/ui';
import { usePolling } from '../hooks';
import { useT } from '../i18n';

/** Every AI generation, valid or not, newest first. */
export function AiDraftsPage() {
  const tr = useT();
  const [params, setParams] = useSearchParams();
  const project = params.get('project') ?? '';
  const page = Number(params.get('page') ?? 0) || 0;
  const projects = usePolling(() => api.qaProjects(), [], 0);
  const drafts = usePolling(() => api.aiDraftPage({ project, page }), [project, page], 0);
  const set = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(changes)) { if (v) next.set(k, v); else next.delete(k); }
    if (!('page' in changes)) next.delete('page');
    setParams(next);
  };

  return (
    <>
      <p><Link to="/qa/test-cases">← {tr('Test cases')}</Link></p>
      <Section title={tr('Generation history')} actions={
        <label className="inline">{tr('Project')}
          <select value={project} onChange={e => set({ project: e.target.value })}>
            <option value="">{tr('All projects')}</option>
            {projects.data?.map(p => <option key={p.key} value={p.key}>{p.name}</option>)}
          </select>
        </label>
      }>
        <ErrorBanner error={drafts.error ?? projects.error} />
        {drafts.data && drafts.data.items.length === 0 && <Empty>{tr('Nothing has been generated yet.')}</Empty>}
        {drafts.data && drafts.data.items.length > 0 && (
          <div className="table-wrap"><table>
            <thead><tr><th>{tr('Draft')}</th><th>{tr('Project')}</th><th>{tr('Service')}</th><th>{tr('Result')}</th><th>{tr('Model')}</th><th>{tr('Generated')}</th></tr></thead>
            <tbody>
              {drafts.data.items.map(d => (
                <tr key={d.id}>
                  <td><Link to={`/qa/test-cases/${d.projectKey}/drafts/${d.id}`}>{d.collection?.name ?? tr('Invalid draft')}</Link></td>
                  <td>{d.projectKey}</td>
                  <td>{d.service}</td>
                  <td><span className={`badge ${d.valid ? 'badge-ok' : 'badge-bad'}`}>{d.valid ? tr('{n} cases', { n: d.collection?.cases?.length ?? 0 }) : tr('invalid')}</span></td>
                  <td>{d.model}</td>
                  <td><Time value={d.createdAt} /> {tr('by {who}', { who: d.createdBy })}</td>
                </tr>
              ))}
            </tbody>
          </table></div>
        )}
        {drafts.data && <Pager page={page} totalPages={drafts.data.totalPages} onPage={p => set({ page: String(p) })} />}
      </Section>
    </>
  );
}
