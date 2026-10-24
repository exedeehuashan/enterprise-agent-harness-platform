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
import { useT } from '../i18n';

/** Platform administration for approvers: domain modules, API tokens, webhooks, storage and retention. */
export function AdminPage() {
  const tr = useT();
  const ai = usePolling(() => api.aiStatus(), [], 30000);
  return (
    <>
      <Section title={tr('AI model')}>
        {ai.data && <p className="small">{tr('Provider')} <strong>{ai.data.provider}</strong> ({ai.data.model}) · {tr('{used} of {budget} tokens used today.', { used: ai.data.usedToday.toLocaleString(), budget: ai.data.dailyTokenBudget.toLocaleString() })}
          {ai.data.provider === 'mock' && <span className="muted"> {tr('No model is configured: set EAHP_AI_PROVIDER and its API key in .env.')}</span>}</p>}
      </Section>
      <ModulesPanel />
      <CodeRepositoriesPanel />
      <TokensPanel />
      <WebhooksPanel />
      <StoragePanel />
      <BackupDrillsPanel />
      <AiEvalsPanel />
      <p className="muted small">{tr('Metrics for monitoring are at')} <code>/actuator/prometheus</code> {tr('on the backend (any viewer token).')}</p>
    </>
  );
}
