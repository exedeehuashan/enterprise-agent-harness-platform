import { useState, type FormEvent } from 'react';
import { api, newKey } from '../api/client';
import type { QaProject } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { Empty, ErrorBanner } from './ui';
import { QA_TOOLS } from './qa';
import { useT } from '../i18n';

/** Runs a repository-declared API test collection against one of the project's environments. */
export function RunApiTestsForm({ taskId, onStarted }: { taskId: string; onStarted: () => void }) {
  const tr = useT();
  const projects = usePolling(() => api.qaProjects(), [], 0);
  const [projectKey, setProjectKey] = useState('');
  const [form, setForm] = useState({ ref: '', checkedRef: '', collection: '', environment: '', timeout: 600 });
  const action = useAction();
  const enabled = (projects.data ?? []).filter(p => p.enabled);
  const project: QaProject | undefined = enabled.find(p => p.key === projectKey) ?? enabled[0];
  const catalog = usePolling(() => (project ? api.apiTests(project.key, form.checkedRef || undefined) : Promise.resolve(null)), [project?.key, form.checkedRef], 0);
  const collections = catalog.data?.collections ?? [];
  const environments = (catalog.data?.environments ?? []).filter(e => e.enabled);
  const collection = collections.find(c => c.key === form.collection) ?? collections[0];
  const environment = environments.find(e => e.key === form.environment) ?? environments[0];

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!project || !collection || !environment) return;
    const args: Record<string, unknown> = { project: project.key, collection: collection.key, environment: environment.key };
    if (form.ref.trim()) args.ref = form.ref.trim();
    if (await action.run(() => api.startRun(taskId, newKey('api'), QA_TOOLS.api, form.timeout, args))) onStarted();
  };

  if (!project) return null;
  return (
    <form className="stack" onSubmit={submit}>
      <div className="row">
        <label className="inline">{tr('Project')}
          <select value={project.key} onChange={e => { setProjectKey(e.target.value); setForm({ ...form, collection: '', environment: '', ref: '', checkedRef: '' }); }}>
            {enabled.map(p => <option key={p.key} value={p.key}>{p.name}</option>)}
          </select>
        </label>
        <label className="inline">{tr('Branch, tag or commit')}
          <input value={form.ref} onChange={e => setForm({ ...form, ref: e.target.value })} onBlur={() => setForm(f => ({ ...f, checkedRef: f.ref.trim() }))} placeholder={project.defaultRef} maxLength={100} />
        </label>
        <label className="inline">{tr('Collection')}
          <select value={collection?.key ?? ''} onChange={e => setForm({ ...form, collection: e.target.value })} disabled={!collection}>
            {collections.map(c => <option key={c.key} value={c.key}>{c.name} ({c.cases} cases)</option>)}
          </select>
        </label>
        <label className="inline">{tr('Environment')}
          <select value={environment?.key ?? ''} onChange={e => setForm({ ...form, environment: e.target.value })} disabled={!environment}>
            {environments.map(env => <option key={env.key} value={env.key}>{env.name}</option>)}
          </select>
        </label>
        <label className="inline">{tr('Timeout (s)')}
          <input type="number" min={1} max={1800} value={form.timeout} onChange={e => setForm({ ...form, timeout: Number(e.target.value) })} />
        </label>
        <button type="submit" disabled={action.busy || !collection || !environment || form.checkedRef !== form.ref.trim()}>{tr('Run API tests')}</button>
      </div>
      {catalog.data?.file === 'absent' && <p className="muted small">{tr('No API test file at this commit.')}</p>}
      {catalog.data?.file === 'invalid' && <div className="error" role="alert"><strong>{tr('The API test file is invalid at this commit:')}</strong> {catalog.data.error}</div>}
      {catalog.data && environments.length === 0 && <p className="muted small">No environment is registered for this project; an approver adds one on the Test projects page.</p>}
      {environment && collection && <p className="muted small">Sends requests to {Object.entries(environment.services).map(([k, v]) => `${k} → ${v}`).join(', ')}.</p>}
      <ErrorBanner error={catalog.error ?? action.error} />
    </form>
  );
}

/** A project's test environments; approvers register them (they decide where API tests send requests). */
export function ProjectEnvironments({ project }: { project: QaProject }) {
  const tr = useT();
  const { can } = useSession();
  const environments = usePolling(() => api.environments(project.key), [project.key], 0);
  const action = useAction();
  const [form, setForm] = useState({ key: '', name: '', services: '' });
  const change = async (call: () => Promise<unknown>) => { if (await action.run(call) !== undefined) { setForm({ key: '', name: '', services: '' }); void environments.reload(); } };
  const save = (e: FormEvent) => {
    e.preventDefault();
    // One "service=base URL" per line.
    const services: Record<string, string> = {};
    for (const line of form.services.split('\n')) {
      const i = line.indexOf('=');
      if (i > 0) services[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    }
    void change(() => api.admin.putEnvironment(project.key, form.key.trim(), { name: form.name.trim(), services }));
  };
  return (
    <>
      <h3 className="subhead">{tr('Environments')}</h3>
      <ErrorBanner error={environments.error ?? action.error} />
      {environments.data?.length === 0 && <Empty>{tr('No test environment is registered.')}</Empty>}
      {environments.data?.map(env => (
        <div key={env.key} className="row spread item">
          <div><strong>{env.name}</strong> <code className="muted small">{env.key}</code>{!env.enabled && <span className="badge badge-muted"> {tr('disabled')}</span>}
            {env.ephemeral && <span className="badge badge-warn"> {tr('temporary')}</span>}
            <div className="small">{Object.entries(env.services).map(([k, v]) => <div key={k}><code>{k}</code> → <code className="wrap">{v}</code></div>)}</div></div>
          {can('APPROVER') && !env.ephemeral && <button className="danger" disabled={action.busy}
            onClick={() => window.confirm(`Delete environment ${env.key}?`) && change(() => api.admin.deleteEnvironment(project.key, env.key))}>{tr('Delete')}</button>}
        </div>
      ))}
      {can('APPROVER') && (
        <form className="row" onSubmit={save}>
          <label className="inline">{tr('Key')}<input value={form.key} onChange={e => setForm({ ...form, key: e.target.value })} placeholder="uat" required /></label>
          <label className="inline">{tr('Name')}<input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} required /></label>
          <label className="inline">{tr('Services')}<textarea rows={2} value={form.services} onChange={e => setForm({ ...form, services: e.target.value })}
            placeholder="order-api=http://host:8081" required /></label>
          <button type="submit" className="secondary" disabled={action.busy}>{tr('Save environment')}</button>
        </form>
      )}
    </>
  );
}
