import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Approval } from '../api/types';
import { Empty, ErrorBanner, Section, ShortId, Status, Time } from '../components/ui';
import { useAction, usePolling } from '../hooks';
import { useT } from '../i18n';

export function ApprovalsPage() {
  const tr = useT();
  const [showAll, setShowAll] = useState(false);
  const approvals = usePolling(() => api.approvals(), []);
  const items = (approvals.data?.items ?? []).filter(a => showAll || a.status === 'PENDING');

  return (
    <Section title={tr('Approvals')} actions={
      <label className="inline"><input type="checkbox" checked={showAll} onChange={e => setShowAll(e.target.checked)} /> {tr('Show decided')}</label>
    }>
      <p className="muted small">{tr('Approving binds your decision to the run\'s exact input digest. Review the run snapshot before deciding.')}</p>
      <ErrorBanner error={approvals.error} />
      {approvals.data && items.length === 0 && <Empty>{showAll ? tr('No approvals yet.') : tr('Nothing is waiting for approval.')}</Empty>}
      {items.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{tr('Run')}</th><th>{tr('Status')}</th><th>{tr('Requested')}</th><th>{tr('Expires')}</th><th>{tr('Decided by')}</th><th /></tr></thead>
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
  const tr = useT();
  const action = useAction();
  const decide = async (decision: 'APPROVE' | 'REJECT') => {
    if (decision === 'REJECT' && !window.confirm(tr('Reject this run? It will end as REJECTED.'))) return;
    if (await action.run(() => api.decide(approval.id, decision, approval.inputDigest))) onDone();
  };
  return (
    <div className="stack-tight">
      <div className="row">
        <button onClick={() => decide('APPROVE')} disabled={action.busy}>{tr('Approve')}</button>
        <button className="danger" onClick={() => decide('REJECT')} disabled={action.busy}>{tr('Reject')}</button>
      </div>
      <ErrorBanner error={action.error} />
    </div>
  );
}
