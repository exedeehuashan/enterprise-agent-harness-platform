import { api } from '../api/client';
import { usePolling } from '../hooks';
import { Section } from '../components/ui';
import { ModulesPanel } from '../components/modules';
import { CodeRepositoriesPanel } from '../components/codeAgent';
import { StoragePanel } from '../components/storage';
import { BackupDrillsPanel } from '../components/backupDrills';
import { AiEvalsPanel } from '../components/aiEvals';
import { TokensPanel } from '../components/tokens';
import { WebhooksPanel } from '../components/webhooks';

/** Platform administration for approvers: domain modules, API tokens, webhooks, storage and retention. */
export function AdminPage() {
  const ai = usePolling(() => api.aiStatus(), [], 30000);
  return (
    <>
      <Section title="AI model">
        {ai.data && <p className="small">Provider <strong>{ai.data.provider}</strong> ({ai.data.model}) · {ai.data.usedToday.toLocaleString()} of {ai.data.dailyTokenBudget.toLocaleString()} tokens used today.
          {ai.data.provider === 'mock' && <span className="muted"> No model is configured: set EAHP_AI_PROVIDER and its API key in .env.</span>}</p>}
      </Section>
      <ModulesPanel />
      <CodeRepositoriesPanel />
      <TokensPanel />
      <WebhooksPanel />
      <StoragePanel />
      <BackupDrillsPanel />
      <AiEvalsPanel />
      <p className="muted small">Metrics for monitoring are at <code>/actuator/prometheus</code> on the backend (any viewer token).</p>
    </>
  );
}
