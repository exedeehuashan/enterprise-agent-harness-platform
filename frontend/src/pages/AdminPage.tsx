import { ModulesPanel } from '../components/modules';
import { StoragePanel } from '../components/storage';
import { TokensPanel } from '../components/tokens';
import { WebhooksPanel } from '../components/webhooks';

/** Platform administration for approvers: domain modules, API tokens, webhooks, storage and retention. */
export function AdminPage() {
  return (
    <>
      <ModulesPanel />
      <TokensPanel />
      <WebhooksPanel />
      <StoragePanel />
      <p className="muted small">Metrics for monitoring are at <code>/actuator/prometheus</code> on the backend (any viewer token).</p>
    </>
  );
}
