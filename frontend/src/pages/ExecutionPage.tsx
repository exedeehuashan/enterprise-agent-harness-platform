import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { ErrorBanner, Section, ShortId, Status, Time } from '../components/ui';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';

export function ExecutionPage() {
  const { id = '' } = useParams();
  const { can } = useSession();
  const execution = usePolling(() => api.getExecution(id), [id]);
  const action = useAction();

  if (!execution.data) return <ErrorBanner error={execution.error} />;
  const e = execution.data;
  const cancel = async () => {
    if (!window.confirm('Cancel this plan execution? Remaining steps will be skipped.')) return;
    if (await action.run(() => api.cancelExecution(e.id))) void execution.reload();
  };

  return (
    <>
      <p><Link to={`/tasks/${e.taskId}`}>← Task</Link></p>
      <Section title={`Plan execution ${e.id.slice(0, 8)}`} actions={
        <>
          <Status value={e.status} />
          {can('OPERATOR') && e.status === 'RUNNING' && <button className="danger" onClick={cancel} disabled={action.busy}>Cancel execution</button>}
        </>
      }>
        <ErrorBanner error={action.error} />
        <dl className="meta">
          <dt>Requested by</dt><dd>{e.requestedBy}</dd>
          <dt>Started</dt><dd><Time value={e.createdAt} /></dd>
          <dt>Finished</dt><dd><Time value={e.finishedAt} /></dd>
          <dt>Error</dt><dd>{e.errorCode ?? '—'}</dd>
          <dt>Plan digest</dt><dd><code className="wrap">{e.planDigest}</code></dd>
        </dl>
      </Section>
      <Section title="Steps">
        <div className="table-wrap">
          <table>
            <thead><tr><th>#</th><th>Tool</th><th>Step</th><th>Run</th><th>Run status</th></tr></thead>
            <tbody>
              {e.steps.map(s => (
                <tr key={s.sequence}>
                  <td>{s.sequence}</td>
                  <td>{s.tool} v{s.toolVersion}</td>
                  <td><Status value={s.status} /></td>
                  <td>{s.runId ? <Link to={`/runs/${s.runId}`}><ShortId id={s.runId} /></Link> : <span className="muted">not launched</span>}</td>
                  <td><Status value={s.runStatus} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {e.steps.some(s => s.runStatus === 'WAITING_APPROVAL') && (
          <p className="muted">A step is waiting for approval{can('APPROVER') ? <> — see <Link to="/approvals">Approvals</Link>.</> : '.'}</p>
        )}
      </Section>
    </>
  );
}
