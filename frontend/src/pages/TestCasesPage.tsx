import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { CASE_CATEGORIES, useCategoryLabel } from '../components/aiTests';
import { Empty, ErrorBanner, Pager, Section, Time } from '../components/ui';
import { usePolling } from '../hooks';
import { useSession } from '../session';
import { useT } from '../i18n';

/** Every AI-generated API test case, paged and filtered by project, category and text. Filters live in the URL. */
export function TestCasesPage() {
  const tr = useT();
  const { can } = useSession();
  const label = useCategoryLabel();
  const [params, setParams] = useSearchParams();
  const project = params.get('project') ?? '', category = params.get('category') ?? '', q = params.get('q') ?? '';
  const page = Number(params.get('page') ?? 0) || 0;
  const [search, setSearch] = useState(q);
  const projects = usePolling(() => api.qaProjects(), [], 0);
  const cases = usePolling(() => api.aiTestCases({ project, category, q, page }), [project, category, q, page], 0);

  const set = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(changes)) { if (v) next.set(k, v); else next.delete(k); }
    if (!('page' in changes)) next.delete('page');
    setParams(next);
  };
  const submitSearch = (e: FormEvent) => { e.preventDefault(); set({ q: search.trim() }); };

  return (
    <Section title={tr('Test cases')} actions={<>
      <Link className="button secondary" to="/qa/test-cases/drafts">{tr('Generation history')}</Link>
      {can('OPERATOR') && <Link className="button" to={`/qa/test-cases/generate${project ? `?project=${encodeURIComponent(project)}` : ''}`}>{tr('Generate with AI')}</Link>}
    </>}>
      <p className="muted small">{tr('API test cases written by the AI from OpenAPI documents. Open a case to see its draft, run it or download it.')}</p>
      <form className="row filters" onSubmit={submitSearch}>
        <label className="inline">{tr('Project')}
          <select value={project} onChange={e => set({ project: e.target.value })}>
            <option value="">{tr('All projects')}</option>
            {projects.data?.map(p => <option key={p.key} value={p.key}>{p.name}</option>)}
          </select>
        </label>
        <label className="inline">{tr('Category')}
          <select value={category} onChange={e => set({ category: e.target.value })}>
            <option value="">{tr('All categories')}</option>
            {Object.keys(CASE_CATEGORIES).map(c => <option key={c} value={c}>{label(c)}</option>)}
            <option value="none">{tr('uncategorised')}</option>
          </select>
        </label>
        <label className="inline">{tr('Search')}<input value={search} onChange={e => setSearch(e.target.value)} placeholder={tr('ID, name or service')} maxLength={100} /></label>
        <button type="submit" className="secondary">{tr('Search')}</button>
      </form>
      <ErrorBanner error={cases.error ?? projects.error} />
      {cases.data && <p className="muted small">{tr('{n} cases', { n: cases.data.totalElements })}</p>}
      {cases.data && cases.data.items.length === 0 && <Empty>{tr('No test cases match.')}</Empty>}
      {cases.data && cases.data.items.length > 0 && (
        <div className="table-wrap"><table className="case-list">
          <colgroup><col className="c-id" /><col /><col className="c-category" /><col className="c-service" /><col className="c-draft" /><col className="c-time" /></colgroup>
          <thead><tr><th>{tr('ID')}</th><th>{tr('Name')}</th><th>{tr('Category')}</th><th>{tr('Service')}</th><th>{tr('Draft')}</th><th>{tr('Generated')}</th></tr></thead>
          <tbody>
            {cases.data.items.map(c => (
              <tr key={`${c.draftId}/${c.caseId}`}>
                <td><code>{c.caseId}</code></td>
                <td><Link to={`/qa/test-cases/${c.projectKey}/drafts/${c.draftId}#case-${c.caseId}`}>{c.name}</Link></td>
                <td><span className="badge badge-muted">{label(c.category)}</span></td>
                <td>{c.service}</td>
                <td><Link to={`/qa/test-cases/${c.projectKey}/drafts/${c.draftId}`}>{c.collectionName}</Link></td>
                <td><Time value={c.createdAt} /></td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}
      {cases.data && <Pager page={page} totalPages={cases.data.totalPages} onPage={p => set({ page: String(p) })} />}
    </Section>
  );
}
