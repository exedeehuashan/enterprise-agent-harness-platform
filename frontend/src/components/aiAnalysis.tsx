import { useEffect, useState } from 'react';
import { ApiError, api } from '../api/client';
import type { AiAnalysis } from '../api/types';
import { useAction } from '../hooks';
import { useSession } from '../session';
import { ErrorBanner, Section, Time } from './ui';
import { useT } from '../i18n';

const CATEGORY: Record<string, string> = {
  PRODUCT_DEFECT: 'Product defect', TEST_DEFECT: 'Test defect', ENVIRONMENT: 'Environment', FLAKY: 'Flaky', INFRASTRUCTURE: 'Infrastructure', UNKNOWN: 'Unknown',
};

/** The model's reading of a failed QA run: advisory text from the recorded evidence, never acted on automatically. */
export function AiAnalysisPanel({ runId }: { runId: string }) {
  const tr = useT();
  const { can } = useSession();
  const [analysis, setAnalysis] = useState<AiAnalysis | null>(null);
  const [loadError, setLoadError] = useState<Error | null>(null);
  const action = useAction();
  useEffect(() => {
    let live = true;
    api.aiAnalysis(runId).then(a => { if (live) setAnalysis(a); })
      .catch(e => { if (live && !(e instanceof ApiError && e.status === 404)) setLoadError(e as Error); });
    return () => { live = false; };
  }, [runId]);
  const analyze = async () => { const a = await action.run(() => api.analyzeRun(runId)); if (a) setAnalysis(a); };
  const a = analysis?.analysis;
  return (
    <Section title={tr('AI analysis')} actions={can('OPERATOR') && <button className="secondary" disabled={action.busy} onClick={() => void analyze()}>
      {action.busy ? 'Analysing…' : analysis ? tr('Analyse again') : tr('Analyse with AI')}</button>}>
      <ErrorBanner error={loadError ?? action.error} />
      {!a && !action.busy && <p className="muted small">{tr('Ask the configured model to explain this failure from the run\'s evidence (failing tests, triage and the console log, with secrets redacted).')}</p>}
      {a && analysis && (
        <div className="stack-tight">
          <p><strong>{CATEGORY[a.category] ? tr(CATEGORY[a.category]) : a.category}</strong> <span className="muted small">· {tr('{level} confidence', { level: tr(a.confidence) })}</span></p>
          <p>{a.summary}</p>
          <p className="small"><strong>{tr('Likely cause:')}</strong> {a.likelyCause}</p>
          {a.suggestedActions.length > 0 && <><strong className="small">{tr('Suggested next steps')}</strong><ul className="small">{a.suggestedActions.map((s, i) => <li key={i}>{s}</li>)}</ul></>}
          <p className="muted small">{analysis.provider} / {analysis.model} · {tr('{n} tokens', { n: analysis.inputTokens + analysis.outputTokens })} · <Time value={analysis.createdAt} /> {tr('by {who}', { who: analysis.createdBy })}. {tr('Advisory only: check it against the evidence.')}</p>
        </div>
      )}
    </Section>
  );
}
