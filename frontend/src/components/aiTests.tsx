import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { AiTestDraft, ApiTestCase, QaProject } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { ErrorBanner, Time } from './ui';
import { useT } from '../i18n';

/** Downloads a draft as an .eahp/api-tests.json file to review and commit. */
function download(draft: AiTestDraft) {
  const file = JSON.stringify({ schemaVersion: 1, collections: [draft.collection] }, null, 2) + '\n';
  const url = URL.createObjectURL(new Blob([file], { type: 'application/json' }));
  const a = document.createElement('a');
  a.href = url; a.download = `${draft.collection?.key ?? 'ai-tests'}.api-tests.json`; a.click();
  URL.revokeObjectURL(url);
}

/** What a case checks, in the order the generator is asked to cover them. */
export const CASE_CATEGORIES: Record<string, string> = {
  'happy-path': 'happy path', validation: 'validation', 'not-found': 'not found', idempotency: 'idempotency', concurrency: 'concurrency', 'state-rule': 'state rule',
};

/** Per-category counts and, on demand, every case with its steps. */
function DraftCases({ cases }: { cases: ApiTestCase[] }) {
  const tr = useT();
  const label = (c?: string) => tr(c && CASE_CATEGORIES[c] ? CASE_CATEGORIES[c] : 'uncategorised');
  const counts = new Map<string, number>();
  for (const c of cases) counts.set(label(c.category), (counts.get(label(c.category)) ?? 0) + 1);
  const status = (s?: number | number[]) => (Array.isArray(s) ? s.join('/') : s ?? '');
  return (
    <>
      <div className="row">{[...counts].map(([k, n]) => <span key={k} className="badge">{k} {n}</span>)}</div>
      <details>
        <summary>{tr('Show cases')}</summary>
        <div className="table-wrap"><table>
          <thead><tr><th>{tr('ID')}</th><th>{tr('Name')}</th><th>{tr('Category')}</th><th>{tr('Steps')}</th></tr></thead>
          <tbody>
            {cases.map(c => (
              <tr key={c.id}>
                <td><code>{c.id}</code></td>
                <td>{c.name}</td>
                <td><span className="badge">{label(c.category)}</span></td>
                <td>{(c.steps ?? []).map((s, i) => (
                  <div key={i} className="wrap"><code>{s.request?.method} {s.request?.path}</code> → {status(s.expect?.status)}</div>
                ))}</td>
              </tr>
            ))}
          </tbody>
        </table></div>
      </details>
    </>
  );
}

/** AI-generated API tests: draft a collection from an OpenAPI document, review it, run it against an environment. */
export function AiTestsPanel({ project }: { project: QaProject }) {
  const tr = useT();
  const { can } = useSession();
  const drafts = usePolling(() => api.aiDrafts(project.key), [project.key], 0);
  const environments = usePolling(() => api.environments(project.key), [project.key], 0);
  const action = useAction();
  const [form, setForm] = useState({ specPath: '', service: '', ref: '', instructions: '' });
  const [started, setStarted] = useState<string | null>(null);
  const env = (environments.data ?? []).find(e => e.enabled && !e.ephemeral);
  const generate = async (e: FormEvent) => {
    e.preventDefault();
    const body: Record<string, unknown> = { specPath: form.specPath.trim(), service: form.service.trim() };
    if (form.ref.trim()) body.ref = form.ref.trim();
    if (form.instructions.trim()) body.instructions = form.instructions.trim();
    if (await action.run(() => api.generateAiTests(project.key, body))) void drafts.reload();
  };
  const run = async (id: string) => {
    if (!env) return;
    const r = await action.run(() => api.runAiDraft(project.key, id, env.key));
    if (r) setStarted(r.runId);
  };
  return (
    <>
      <h3 className="subhead">{tr('AI-generated API tests')}</h3>
      <ErrorBanner error={drafts.error ?? action.error} />
      {can('OPERATOR') && (
        <form className="row" onSubmit={generate}>
          <label className="inline">{tr('OpenAPI file')}<input value={form.specPath} onChange={e => setForm({ ...form, specPath: e.target.value })} placeholder="openapi/order-api.json" required /></label>
          <label className="inline">{tr('Service')}<input value={form.service} onChange={e => setForm({ ...form, service: e.target.value })} placeholder="order-api" required /></label>
          <label className="inline">{tr('Ref')}<input value={form.ref} onChange={e => setForm({ ...form, ref: e.target.value })} placeholder={project.defaultRef} /></label>
          <label className="inline">{tr('Focus (optional)')}<input value={form.instructions} onChange={e => setForm({ ...form, instructions: e.target.value })} maxLength={2000} /></label>
          <button type="submit" className="secondary" disabled={action.busy}>{action.busy ? tr('Generating…') : tr('Generate with AI')}</button>
        </form>
      )}
      {started && <p className="small">{tr('Started')} <Link to={`/runs/${started}`}>{tr('the draft\'s run')}</Link>.</p>}
      {drafts.data?.map(d => (
        <article key={d.id} className="item small">
          <div className="row spread">
            <div className="fill"><strong>{d.collection?.name ?? tr('Invalid draft')}</strong> <code className="muted">{d.collection?.key}</code>{' '}
              <span className={`badge ${d.valid ? 'badge-ok' : 'badge-bad'}`}>{d.valid ? tr('{n} cases', { n: d.collection?.cases?.length ?? 0 }) : tr('invalid')}</span>
              <div className="muted">{d.specPath} @ <code>{d.commitSha.slice(0, 7)}</code> → {d.service} · {d.provider}/{d.model} · <Time value={d.createdAt} /> {tr('by {who}', { who: d.createdBy })}</div>
              {d.error && <div className="error">{d.error}</div>}
              {d.notes && <div>{d.notes}</div>}
              {d.valid && d.collection?.cases && <DraftCases cases={d.collection.cases} />}
            </div>
            {d.valid && <div className="row">
              <button className="secondary" onClick={() => download(d)}>{tr('Download')}</button>
              {can('OPERATOR') && <button className="secondary" disabled={action.busy || !env} title={env ? tr('Run against {name}', { name: env.name }) : tr('No environment registered')} onClick={() => void run(d.id)}>
                {env ? tr('Run against {name}', { name: env.key }) : tr('No environment')}</button>}
            </div>}
          </div>
        </article>
      ))}
    </>
  );
}
