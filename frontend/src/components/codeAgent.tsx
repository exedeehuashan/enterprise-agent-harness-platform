import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, newKey } from '../api/client';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { Empty, ErrorBanner, Section } from './ui';
import { useT } from '../i18n';

/** Repositories the code agent may work on; approvers register them and decide whether a branch may be pushed. */
export function CodeRepositoriesPanel() {
  const tr = useT();
  const { can } = useSession();
  const repos = usePolling(() => api.codeRepositories(), [], 0);
  const action = useAction();
  return (
    <Section title={tr('Code agent repositories')} actions={can('APPROVER') && <Link className="button secondary" to="/admin/code-repositories/new">{tr('Add repository')}</Link>}>
      <p className="muted small">Claude Code may only work on these repositories, and every run needs an approver. With pushing allowed and EAHP_GITHUB_WRITE_TOKEN set,
        EAHP pushes the agent's change to a new branch and opens a draft pull request; otherwise only the diff is recorded.</p>
      <ErrorBanner error={repos.error ?? action.error} />
      {repos.data?.length === 0 && <Empty>{tr('No repositories are registered.')}</Empty>}
      {repos.data?.map(r => (
        <div key={r.key} className="row spread item small">
          <div><strong>{r.name}</strong> <code className="muted">{r.key}</code> {r.allowPush ? <span className="badge badge-warn">{tr('push + draft PR')}</span> : <span className="badge badge-muted">{tr('diff only')}</span>}
            {!r.enabled && <span className="badge badge-muted"> {tr('disabled')}</span>}<div className="muted"><code className="wrap">{r.repositoryUrl}</code> @ {r.defaultRef}</div></div>
          {can('APPROVER') && <button className="danger" disabled={action.busy} onClick={() => window.confirm(`Remove ${r.key}?`) && action.run(() => api.deleteCodeRepository(r.key)).then(() => repos.reload())}>{tr('Remove')}</button>}
        </div>
      ))}
    </Section>
  );
}

/** Asks Claude Code to make a change; the run waits for an approver before anything executes. */
export function RunCodeAgentForm({ taskId, onStarted }: { taskId: string; onStarted: () => void }) {
  const tr = useT();
  const repos = usePolling(() => api.codeRepositories(), [], 0);
  const action = useAction();
  const enabled = (repos.data ?? []).filter(r => r.enabled);
  const [form, setForm] = useState({ repository: '', ref: '', instructions: '', maxTurns: 40, timeout: 1800 });
  const repo = enabled.find(r => r.key === form.repository) ?? enabled[0];
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!repo) return;
    const args: Record<string, unknown> = { repository: repo.key, instructions: form.instructions, maxTurns: form.maxTurns };
    if (form.ref.trim()) args.ref = form.ref.trim();
    if (await action.run(() => api.startRun(taskId, newKey('code'), 'CODE_AGENT_RUN', form.timeout, args))) { setForm({ ...form, instructions: '' }); onStarted(); }
  };
  if (repos.data && enabled.length === 0) return <Empty>{tr('No repository is registered for the code agent (Administration page).')}</Empty>;
  if (!repo) return null;
  return (
    <form className="stack" onSubmit={submit}>
      <div className="row">
        <label className="inline">{tr('Repository')}<select value={repo.key} onChange={e => setForm({ ...form, repository: e.target.value })}>
          {enabled.map(r => <option key={r.key} value={r.key}>{r.name}</option>)}</select></label>
        <label className="inline">{tr('Branch, tag or commit')}<input value={form.ref} onChange={e => setForm({ ...form, ref: e.target.value })} placeholder={repo.defaultRef} maxLength={100} /></label>
        <label className="inline">{tr('Max turns')}<input type="number" min={1} max={200} value={form.maxTurns} onChange={e => setForm({ ...form, maxTurns: Number(e.target.value) })} /></label>
        <label className="inline">{tr('Timeout (s)')}<input type="number" min={60} max={3600} value={form.timeout} onChange={e => setForm({ ...form, timeout: Number(e.target.value) })} /></label>
      </div>
      <label>{tr('Instructions for Claude Code')}<textarea rows={4} value={form.instructions} onChange={e => setForm({ ...form, instructions: e.target.value })} minLength={10} maxLength={20000} required
        placeholder="For example: make OrderTotalsTest pass without changing the test; keep the change minimal." /></label>
      <p className="muted small">{tr('The run waits for an approver.')} {repo.allowPush ? tr('Its change is pushed to a new branch with a draft pull request (if a write token is configured).') : tr('Only the diff is recorded.')}</p>
      <div><button type="submit" disabled={action.busy}>{tr('Request code change')}</button></div>
      <ErrorBanner error={repos.error ?? action.error} />
    </form>
  );
}
