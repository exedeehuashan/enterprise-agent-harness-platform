import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { AiTestDraft, QaProject } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { ErrorBanner, Time } from './ui';

/** Downloads a draft as an .eahp/api-tests.json file to review and commit. */
function download(draft: AiTestDraft) {
  const file = JSON.stringify({ schemaVersion: 1, collections: [draft.collection] }, null, 2) + '\n';
  const url = URL.createObjectURL(new Blob([file], { type: 'application/json' }));
  const a = document.createElement('a');
  a.href = url; a.download = `${draft.collection?.key ?? 'ai-tests'}.api-tests.json`; a.click();
  URL.revokeObjectURL(url);
}

/** AI-generated API tests: draft a collection from an OpenAPI document, review it, run it against an environment. */
export function AiTestsPanel({ project }: { project: QaProject }) {
  const { can } = useSession();
  const drafts = usePolling(() => api.aiDrafts(project.key), [project.key], 0);
  const environments = usePolling(() => api.environments(project.key), [project.key], 0);
  const action = useAction();
  const [form, setForm] = useState({ specPath: '', service: '', ref: '', instructions: '' });
  const [started, setStarted] = useState<string | null>(null);
  const env = (environments.data ?? []).find(e => e.enabled);
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
      <h3 className="subhead">AI-generated API tests</h3>
      <ErrorBanner error={drafts.error ?? action.error} />
      {can('OPERATOR') && (
        <form className="row" onSubmit={generate}>
          <label className="inline">OpenAPI file<input value={form.specPath} onChange={e => setForm({ ...form, specPath: e.target.value })} placeholder="openapi/order-api.json" required /></label>
          <label className="inline">Service<input value={form.service} onChange={e => setForm({ ...form, service: e.target.value })} placeholder="order-api" required /></label>
          <label className="inline">Ref<input value={form.ref} onChange={e => setForm({ ...form, ref: e.target.value })} placeholder={project.defaultRef} /></label>
          <label className="inline">Focus (optional)<input value={form.instructions} onChange={e => setForm({ ...form, instructions: e.target.value })} maxLength={2000} /></label>
          <button type="submit" className="secondary" disabled={action.busy}>{action.busy ? 'Generating…' : 'Generate with AI'}</button>
        </form>
      )}
      {started && <p className="small">Started <Link to={`/runs/${started}`}>the draft's run</Link>.</p>}
      {drafts.data?.map(d => (
        <article key={d.id} className="item small">
          <div className="row spread">
            <div><strong>{d.collection?.name ?? 'Invalid draft'}</strong> <code className="muted">{d.collection?.key}</code>{' '}
              <span className={`badge ${d.valid ? 'badge-ok' : 'badge-bad'}`}>{d.valid ? `${d.collection?.cases?.length ?? 0} cases` : 'invalid'}</span>
              <div className="muted">{d.specPath} @ <code>{d.commitSha.slice(0, 7)}</code> → {d.service} · {d.provider}/{d.model} · <Time value={d.createdAt} /> by {d.createdBy}</div>
              {d.error && <div className="error">{d.error}</div>}
              {d.notes && <div>{d.notes}</div>}
            </div>
            {d.valid && <div className="row">
              <button className="secondary" onClick={() => download(d)}>Download</button>
              {can('OPERATOR') && <button className="secondary" disabled={action.busy || !env} title={env ? `Run against ${env.name}` : 'No environment registered'} onClick={() => void run(d.id)}>
                {env ? `Run against ${env.key}` : 'No environment'}</button>}
            </div>}
          </div>
        </article>
      ))}
    </>
  );
}
