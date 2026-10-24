import { api } from '../api/client';
import { useAction, usePolling } from '../hooks';
import { Empty, ErrorBanner, Section, Time } from './ui';

/**
 * The AI evaluation set (P44): fixed inputs for failure analysis, test generation and planning, graded automatically.
 * Running it spends real tokens on the configured provider.
 */
export function AiEvalsPanel() {
  const runs = usePolling(() => api.aiEvals(), [], 5000);
  const action = useAction();
  const running = (runs.data ?? []).some(r => r.status === 'RUNNING');
  const latest = runs.data?.[0];
  const run = async () => { if (await action.run(() => api.runAiEvals()) !== undefined) void runs.reload(); };
  return (
    <Section title="AI evaluation" actions={<button className="secondary" disabled={action.busy || running}
      onClick={() => window.confirm('Run the evaluation set against the configured model? It uses real tokens.') && void run()}>{running ? 'Evaluating…' : 'Run evaluation'}</button>}>
      <p className="muted small">Twelve fixed cases check failure analysis (categories, named tests, resistance to instructions hidden in test output),
        API test generation (valid collections, negative cases, only paths from the spec) and planning (valid plans, right tools, injected tools refused).</p>
      <ErrorBanner error={runs.error ?? action.error} />
      {runs.data?.length === 0 && <Empty>No evaluation has run yet.</Empty>}
      {runs.data && runs.data.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>Started</th><th>Model</th><th>Score</th><th>Tokens</th></tr></thead>
          <tbody>{runs.data.map(r => (
            <tr key={r.id}>
              <td className="small"><Time value={r.startedAt} /><div className="muted">set v{r.setVersion}</div></td>
              <td className="small">{r.provider} / {r.model}</td>
              <td><span className={`badge ${r.status === 'RUNNING' ? 'badge-warn' : r.passed === r.total && r.status === 'DONE' ? 'badge-ok' : 'badge-bad'}`}>
                {r.status === 'RUNNING' ? `running ${r.total}…` : `${r.passed} / ${r.total}`}</span></td>
              <td className="small">{r.inputTokens.toLocaleString()} in · {r.outputTokens.toLocaleString()} out</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      {latest?.results?.filter(c => !c.passed).map(c => (
        <div key={c.id} className="small error">{c.id} {c.name}: {c.error ?? c.checks?.filter(k => !k.passed).map(k => k.check + (k.detail ? ` (${k.detail})` : '')).join('; ')}</div>
      ))}
    </Section>
  );
}
