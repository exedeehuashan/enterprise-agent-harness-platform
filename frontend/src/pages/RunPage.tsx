import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { Empty, ErrorBanner, Section, ShortId, Status, Time } from '../components/ui';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { DecisionButtons } from './ApprovalsPage';
import { ArtifactList, QaEvidence, isQaTool, runLabel } from '../components/qa';

const ACTIVE = ['QUEUED', 'RUNNING', 'WAITING_APPROVAL'];

export function RunPage() {
  const { id = '' } = useParams();
  const { can } = useSession();
  const run = usePolling(() => api.getRun(id), [id]);
  const invocations = usePolling(() => api.invocations(id), [id]);
  const audit = usePolling(() => (can('APPROVER') ? api.audit(id) : Promise.resolve(null)), [id]);
  const approval = usePolling(() => {
    const approvalId = run.data?.approvalId;
    return can('APPROVER') && approvalId ? api.approval(approvalId) : Promise.resolve(null);
  }, [id, run.data?.approvalId]);
  const action = useAction();

  if (!run.data) return <ErrorBanner error={run.error} />;
  const r = run.data;
  const cancel = async () => { if (await action.run(() => api.cancelRun(r.id))) void run.reload(); };

  return (
    <>
      <p><Link to={`/tasks/${r.taskId}`}>← Task</Link></p>
      <Section title={`Run ${r.id.slice(0, 8)} · ${runLabel(r)}`} actions={
        <>
          <Status value={r.status} />
          {can('OPERATOR') && ACTIVE.includes(r.status) && !r.cancelRequested && (
            <button className="danger" onClick={cancel} disabled={action.busy}>Cancel run</button>
          )}
        </>
      }>
        <ErrorBanner error={action.error} />
        {r.cancelRequested && r.status === 'RUNNING' && <p className="muted">Cancellation requested; waiting for the worker.</p>}
        <dl className="meta">
          <dt>Tool</dt><dd>{r.tool} v{r.toolVersion} on {r.target}</dd>
          <dt>Snapshot</dt><dd>{r.snapshotTitle}{r.snapshotDescription ? ` — ${r.snapshotDescription}` : ''}</dd>
          <dt>Timeout</dt><dd>{r.timeoutSeconds}s (deadline <Time value={r.deadlineAt} />)</dd>
          <dt>Attempts</dt><dd>{r.attemptCount}</dd>
          <dt>Created</dt><dd><Time value={r.createdAt} /></dd>
          <dt>Finished</dt><dd><Time value={r.finishedAt} /></dd>
          <dt>Error</dt><dd>{r.errorCode ?? '—'}</dd>
          <dt>Input digest</dt><dd><code className="wrap">{r.inputDigest}</code></dd>
          {r.arguments && <><dt>Arguments</dt><dd><code className="wrap">{JSON.stringify(r.arguments)}</code></dd></>}
        </dl>
      </Section>

      {isQaTool(r.tool) && <QaEvidence run={r} />}

      {r.approvalId && (
        <Section title="Approval">
          {approval.data ? (
            <div className="row spread">
              <div className="row"><Status value={approval.data.status} />
                {approval.data.decidedBy && <span className="muted small">by {approval.data.decidedBy}</span>}
                <span className="muted small">expires <Time value={approval.data.expiresAt} /></span></div>
              {approval.data.status === 'PENDING' && <DecisionButtons approval={approval.data} onDone={() => { void run.reload(); void approval.reload(); }} />}
            </div>
          ) : <p className="muted">{can('APPROVER') ? 'Loading…' : 'Waiting for an approver.'}</p>}
        </Section>
      )}

      <Section title="Steps">
        {r.steps.map(s => (
          <article key={s.sequence} className="item">
            <div className="row"><strong>#{s.sequence}</strong>{s.tool}<Status value={s.status} /></div>
            {s.output && <pre>{s.output}</pre>}
          </article>
        ))}
      </Section>

      <ArtifactList runId={r.id} />

      <Section title="Tool invocations">
        <ErrorBanner error={invocations.error} />
        {invocations.data?.length === 0 && <Empty>No tool has been dispatched for this run.</Empty>}
        {invocations.data && invocations.data.length > 0 && (
          <div className="table-wrap"><table>
            <thead><tr><th>Attempt</th><th>Invocation</th><th>Status</th><th>Started</th><th>Error</th></tr></thead>
            <tbody>{invocations.data.map(i => (
              <tr key={i.id}><td>{i.attempt}</td><td><ShortId id={i.id} /></td><td><Status value={i.status} /></td>
                <td><Time value={i.startedAt} /></td><td>{i.errorCode ?? ''}</td></tr>
            ))}</tbody>
          </table></div>
        )}
      </Section>

      {can('APPROVER') && (
        <Section title="Audit trail">
          <ErrorBanner error={audit.error} />
          {audit.data && (
            <ol className="timeline">
              {audit.data.items.map(e => (
                <li key={e.id}><Time value={e.occurredAt} /> <strong>{e.actor}</strong> {e.action}{e.code ? ` (${e.code})` : ''}</li>
              ))}
            </ol>
          )}
        </Section>
      )}
    </>
  );
}
