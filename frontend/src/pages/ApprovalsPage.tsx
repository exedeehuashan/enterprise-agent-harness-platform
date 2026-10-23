import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Approval } from '../api/types';
import { Empty, ErrorBanner, Section, ShortId, Status, Time } from '../components/ui';
import { useAction, usePolling } from '../hooks';

export function ApprovalsPage() {
  const [showAll, setShowAll] = useState(false);
  const approvals = usePolling(() => api.approvals(), []);
  const items = (approvals.data?.items ?? []).filter(a => showAll || a.status === 'PENDING');

  return (
    <Section title="Approvals" actions={
      <label className="inline"><input type="checkbox" checked={showAll} onChange={e => setShowAll(e.target.checked)} /> Show decided</label>
    }>
      <p className="muted small">Approving binds your decision to the run's exact input digest. Review the run snapshot before deciding.</p>
      <ErrorBanner error={approvals.error} />
      {approvals.data && items.length === 0 && <Empty>{showAll ? 'No approvals yet.' : 'Nothing is waiting for approval.'}</Empty>}
      {items.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>Run</th><th>Status</th><th>Requested</th><th>Expires</th><th>Decided by</th><th /></tr></thead>
            <tbody>
              {items.map(a => (
                <tr key={a.id}>
                  <td><Link to={`/runs/${a.runId}`}><ShortId id={a.runId} /></Link></td>
                  <td><Status value={a.status} /></td>
                  <td><Time value={a.requestedAt} /></td>
                  <td><Time value={a.expiresAt} /></td>
                  <td>{a.decidedBy ?? ''}</td>
                  <td>{a.status === 'PENDING' && <DecisionButtons approval={a} onDone={approvals.reload} />}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Section>
  );
}

export function DecisionButtons({ approval, onDone }: { approval: Approval; onDone: () => void }) {
  const action = useAction();
  const decide = async (decision: 'APPROVE' | 'REJECT') => {
    if (decision === 'REJECT' && !window.confirm('Reject this run? It will end as REJECTED.')) return;
    if (await action.run(() => api.decide(approval.id, decision, approval.inputDigest))) onDone();
  };
  return (
    <div className="stack-tight">
      <div className="row">
        <button onClick={() => decide('APPROVE')} disabled={action.busy}>Approve</button>
        <button className="danger" onClick={() => decide('REJECT')} disabled={action.busy}>Reject</button>
      </div>
      <ErrorBanner error={action.error} />
    </div>
  );
}
