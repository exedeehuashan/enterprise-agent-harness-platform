import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { ErrorBanner, Section, ShortId, Status, Time } from '../components/ui';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { useT } from '../i18n';

export function ExecutionPage() {
  const tr = useT();
  const { id = '' } = useParams();
  const { can } = useSession();
  const execution = usePolling(() => api.getExecution(id), [id]);
  const action = useAction();

  if (!execution.data) return <ErrorBanner error={execution.error} />;
  const e = execution.data;
  const cancel = async () => {
    if (!window.confirm(tr('Cancel this plan execution? Remaining steps will be skipped.'))) return;
    if (await action.run(() => api.cancelExecution(e.id))) void execution.reload();
  };

  return (
    <>
      <p><Link to={`/tasks/${e.taskId}`}>{tr('← Task')}</Link></p>
      <Section title={`Plan execution ${e.id.slice(0, 8)}`} actions={
        <>
          <Status value={e.status} />
          {can('OPERATOR') && e.status === 'RUNNING' && <button className="danger" onClick={cancel} disabled={action.busy}>{tr('Cancel execution')}</button>}
        </>
      }>
        <ErrorBanner error={action.error} />
        <dl className="meta">
          <dt>{tr('Requested by')}</dt><dd>{e.requestedBy}</dd>
          <dt>{tr('Started')}</dt><dd><Time value={e.createdAt} /></dd>
          <dt>{tr('Finished')}</dt><dd><Time value={e.finishedAt} /></dd>
          <dt>{tr('Error')}</dt><dd>{e.errorCode ?? '—'}</dd>
          <dt>{tr('Plan digest')}</dt><dd><code className="wrap">{e.planDigest}</code></dd>
        </dl>
      </Section>
      <Section title={tr('Steps')}>
        <div className="table-wrap">
          <table>
            <thead><tr><th>#</th><th>{tr('Tool')}</th><th>{tr('Step')}</th><th>{tr('Run')}</th><th>{tr('Run status')}</th></tr></thead>
            <tbody>
              {e.steps.map(s => (
                <tr key={s.sequence}>
                  <td>{s.sequence}</td>
                  <td>{s.tool} v{s.toolVersion}</td>
                  <td><Status value={s.status} /></td>
                  <td>{s.runId ? <Link to={`/runs/${s.runId}`}><ShortId id={s.runId} /></Link> : <span className="muted">{tr('not launched')}</span>}</td>
                  <td><Status value={s.runStatus} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {e.steps.some(s => s.runStatus === 'WAITING_APPROVAL') && (
          <p className="muted">A step is waiting for approval{can('APPROVER') ? <> {tr('— see')} <Link to="/approvals">{tr('Approvals')}</Link>.</> : '.'}</p>
        )}
      </Section>
    </>
  );
}
