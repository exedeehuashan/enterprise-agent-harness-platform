import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { sourceLink, useFeatureLabel } from '../components/aiCalls';
import { ErrorBanner, Section, Time } from '../components/ui';
import { usePolling } from '../hooks';
import { useT } from '../i18n';
import { CallStatus } from './AiCallsPage';

/** Answers are JSON; show them indented when they parse. */
function pretty(text: string) {
  try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; }
}

/** One AI call: what it was for, and exactly what EAHP sent to the model and what came back. */
export function AiCallPage() {
  const tr = useT();
  const featureLabel = useFeatureLabel();
  const { id = '' } = useParams();
  const detail = usePolling(() => api.aiCall(id), [id], 0);
  const d = detail.data;
  const c = d?.call;
  const src = c ? sourceLink(c.sourceType, c.sourceId) : null;

  return (
    <>
      <p><Link to="/admin/ai-calls">← {tr('AI calls')}</Link></p>
      <ErrorBanner error={detail.error} />
      {d && c && (
        <>
          <Section title={`${featureLabel(c.feature)} #${c.id}`}>
            <dl className="meta">
              <dt>{tr('Result')}</dt><dd><CallStatus status={c.status} error={c.error} /></dd>
              <dt>{tr('For')}</dt><dd>{src ? <Link to={src.to}>{tr(src.label)}</Link> : '—'}{c.sourceId && <> <code className="wrap muted">{c.sourceId}</code></>}</dd>
              <dt>{tr('Model')}</dt><dd>{c.provider}/{c.model}</dd>
              <dt>{tr('Tokens')}</dt><dd>{tr('{in} in · {out} out', { in: c.inputTokens.toLocaleString(), out: c.outputTokens.toLocaleString() })}
                {c.maxOutputTokens != null && <span className="muted small"> · {tr('output limit {n}', { n: c.maxOutputTokens.toLocaleString() })}</span>}</dd>
              <dt>{tr('Duration')}</dt><dd>{c.durationMs == null ? '—' : `${(c.durationMs / 1000).toFixed(1)} s`}</dd>
              <dt>{tr('Started')}</dt><dd><Time value={c.createdAt} /> {tr('by {who}', { who: c.actor })}</dd>
            </dl>
          </Section>
          {!d.contentStored ? (
            <Section title={tr('Prompt and answer')}>
              <p className="muted small">{d.contentExpiredAt
                ? <>{tr('The prompt and answer were removed by the retention policy on')} <Time value={d.contentExpiredAt} />.</>
                : tr('The prompt and answer of this call were not stored: it was made before the AI call log existed, EAHP_AI_LOG_STORE_PROMPTS is false, or it was refused before it was sent.')}</p>
            </Section>
          ) : (
            <>
              <Section title={tr('Instructions to the model (system prompt)')}>
                <p className="muted small">{tr('Fixed by EAHP for this feature; it sets the rules the model must follow.')}</p>
                <pre className="prompt">{d.systemPrompt}</pre>
              </Section>
              <Section title={tr('Input sent (user prompt)')}>
                <p className="muted small">{tr('The data EAHP collected for this call, with secrets replaced by [REDACTED].')}</p>
                <pre className="prompt">{d.userPrompt}</pre>
              </Section>
              <Section title={tr('Answer from the model')}>
                <p className="muted small">{tr('The raw answer before EAHP validated it; EAHP treats it as untrusted data.')}</p>
                {d.answer ? <pre className="prompt">{pretty(d.answer)}</pre> : <p className="muted small">{tr('No answer was received.')}</p>}
              </Section>
            </>
          )}
        </>
      )}
    </>
  );
}
