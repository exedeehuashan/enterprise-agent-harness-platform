import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, newKey } from '../api/client';
import type { ModelCall, Page, PlanExecution, Task } from '../api/types';
import { RunSuiteForm, isQaTool, runLabel } from '../components/qa';
import { RunApiTestsForm } from '../components/qaApiTests';
import { Empty, ErrorBanner, Section, ShortId, Status, Time } from '../components/ui';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';

export function TaskDetailPage() {
  const { id = '' } = useParams();
  const task = usePolling(() => api.getTask(id), [id]);
  const executions = usePolling(() => api.executions(id), [id]);

  if (!task.data) return <ErrorBanner error={task.error} />;
  return (
    <>
      <p><Link to="/">← Tasks</Link></p>
      <TaskHeader task={task.data.task} etag={task.data.etag} onSaved={task.reload} />
      <Runs taskId={id} />
      <Plans taskId={id} executions={executions} />
      <Executions executions={executions} />
    </>
  );
}

function TaskHeader({ task, etag, onSaved }: { task: Task; etag: string; onSaved: () => void }) {
  const { can } = useSession();
  const [editing, setEditing] = useState<{ title: string; description: string; etag: string } | null>(null);
  const action = useAction();

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (!editing) return;
    const saved = await action.run(() => api.editTask(task.id, editing.etag, {
      title: editing.title.trim(), description: editing.description.trim() || null,
    }));
    if (saved) { setEditing(null); onSaved(); }
  };

  return (
    <Section title={task.title} actions={
      <>
        <Status value={task.status} />
        {can('OPERATOR') && !editing && (
          <button className="secondary" onClick={() => setEditing({ title: task.title, description: task.description ?? '', etag })}>Edit</button>
        )}
      </>
    }>
      {editing ? (
        <form className="stack" onSubmit={save}>
          <label>Title<input value={editing.title} onChange={e => setEditing({ ...editing, title: e.target.value })} maxLength={255} required /></label>
          <label>Description<textarea value={editing.description} rows={3} maxLength={10000}
            onChange={e => setEditing({ ...editing, description: e.target.value })} /></label>
          <ErrorBanner error={action.error} />
          <div className="row">
            <button type="submit" disabled={action.busy || !editing.title.trim()}>Save</button>
            <button type="button" className="secondary" onClick={() => { setEditing(null); action.clear(); }}>Cancel</button>
          </div>
        </form>
      ) : (
        <>
          <p className="pre">{task.description ?? <span className="muted">No description</span>}</p>
          <dl className="meta">
            <dt>ID</dt><dd><code>{task.id}</code></dd>
            <dt>Version</dt><dd>{task.version}</dd>
            <dt>Created</dt><dd><Time value={task.createdAt} /></dd>
            <dt>Updated</dt><dd><Time value={task.updatedAt} /></dd>
          </dl>
        </>
      )}
    </Section>
  );
}

function Runs({ taskId }: { taskId: string }) {
  const { can } = useSession();
  const runs = usePolling(() => api.runs(taskId), [taskId]);
  const tools = usePolling(() => api.tools(), [], 0);
  const [tool, setTool] = useState('DEMO_ANALYZE');
  const [timeout, setTimeoutSeconds] = useState(30);
  const action = useAction();

  const start = async (e: FormEvent) => {
    e.preventDefault();
    if (await action.run(() => api.startRun(taskId, newKey('run'), tool, timeout))) void runs.reload();
  };

  return (
    <>
    {can('OPERATOR') && (
      <Section title="Run test suite">
        <RunSuiteForm taskId={taskId} onStarted={() => void runs.reload()} />
      </Section>
    )}
    {can('OPERATOR') && (
      <Section title="Run API tests">
        <RunApiTestsForm taskId={taskId} onStarted={() => void runs.reload()} />
      </Section>
    )}
    <Section title="Runs">
      {can('OPERATOR') && (
        <form className="row" onSubmit={start}>
          <label className="inline">Tool
            <select value={tool} onChange={e => setTool(e.target.value)}>
              {(tools.data ?? []).filter(t => t.enabled && !isQaTool(t.name)).map(t => (
                <option key={t.name} value={t.name}>{t.name}{t.requiresApproval ? ' (needs approval)' : ''}</option>
              ))}
            </select>
          </label>
          <label className="inline">Timeout (s)
            <input type="number" min={1} max={300} value={timeout} onChange={e => setTimeoutSeconds(Number(e.target.value))} />
          </label>
          <button type="submit" disabled={action.busy}>Start run</button>
        </form>
      )}
      <ErrorBanner error={action.error ?? runs.error} />
      {runs.data && runs.data.items.length === 0 && <Empty>No runs yet.</Empty>}
      {runs.data && runs.data.items.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>Run</th><th>What</th><th>Status</th><th>Created</th><th>Error</th></tr></thead>
            <tbody>
              {runs.data.items.map(r => (
                <tr key={r.id}>
                  <td><Link to={`/runs/${r.id}`}><ShortId id={r.id} /></Link></td>
                  <td>{runLabel(r)}</td>
                  <td><Status value={r.status} /></td>
                  <td><Time value={r.createdAt} /></td>
                  <td>{r.errorCode ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Section>
    </>
  );
}

type ExecutionsState = ReturnType<typeof usePolling<Page<PlanExecution>>>;

function Plans({ taskId, executions }: { taskId: string; executions: ExecutionsState }) {
  const { can } = useSession();
  const plans = usePolling(() => api.plans(taskId), [taskId]);
  const action = useAction();
  const executed = new Set((executions.data?.items ?? []).map(e => e.modelCallId));

  const plan = async () => { if (await action.run(() => api.startPlan(taskId, newKey('plan')))) void plans.reload(); };
  const execute = async (call: ModelCall) => {
    if (await action.run(() => api.executePlan(call.id, newKey('exec')))) { void plans.reload(); void executions.reload(); }
  };

  return (
    <Section title="Plans" actions={can('OPERATOR') && <button onClick={plan} disabled={action.busy}>Request plan</button>}>
      <p className="muted small">Plans come from the configured model provider. A validated plan is a proposal only; executing it runs each step through the normal run and approval path.</p>
      <ErrorBanner error={action.error ?? plans.error} />
      {plans.data && plans.data.items.length === 0 && <Empty>No plans yet.</Empty>}
      {plans.data?.items.map(call => (
        <article key={call.id} className="item">
          <div className="row spread">
            <div className="row"><ShortId id={call.id} /><Status value={call.status} /><span className="muted small">{call.provider}/{call.model}</span></div>
            <div className="row">
              {call.errorCode && <span className="muted small">{call.errorCode}</span>}
              {can('OPERATOR') && call.status === 'VALIDATED' && !executed.has(call.id) && (
                <button onClick={() => execute(call)} disabled={action.busy}>Execute plan</button>
              )}
            </div>
          </div>
          {call.plan && (
            <>
              <p>{call.plan.summary}</p>
              <ol className="steps">
                {call.plan.steps.map((s, i) => <li key={i}>{s.tool} v{s.toolVersion} on {s.target}, timeout {s.timeoutSeconds}s</li>)}
              </ol>
            </>
          )}
        </article>
      ))}
    </Section>
  );
}

function Executions({ executions }: { executions: ExecutionsState }) {
  return (
    <Section title="Plan executions">
      <ErrorBanner error={executions.error} />
      {executions.data && executions.data.items.length === 0 && <Empty>No plan executions yet.</Empty>}
      {executions.data?.items.map((e: PlanExecution) => (
        <article key={e.id} className="item">
          <div className="row spread">
            <div className="row"><Link to={`/executions/${e.id}`}><ShortId id={e.id} /></Link><Status value={e.status} />
              {e.errorCode && <span className="muted small">{e.errorCode}</span>}</div>
            <span className="muted small">by {e.requestedBy}, <Time value={e.createdAt} /></span>
          </div>
          <div className="row">{e.steps.map(s => <Status key={s.sequence} value={s.status} />)}</div>
        </article>
      ))}
    </Section>
  );
}
