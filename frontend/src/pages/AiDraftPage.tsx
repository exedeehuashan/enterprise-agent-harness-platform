import { useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { CasesTable, CategoryCounts, download } from '../components/aiTests';
import { ErrorBanner, Section, Time } from '../components/ui';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { useT } from '../i18n';

/** One AI draft: what it was generated from, the model's notes, every case, and download / run. */
export function AiDraftPage() {
  const tr = useT();
  const { project = '', id = '' } = useParams();
  const { hash } = useLocation();
  const { can } = useSession();
  const draft = usePolling(() => api.aiDraft(project, id), [project, id], 0);
  const environments = usePolling(() => api.environments(project), [project], 0);
  const runnable = (environments.data ?? []).filter(e => e.enabled && !e.ephemeral);
  const [env, setEnv] = useState('');
  const [started, setStarted] = useState<string | null>(null);
  const action = useAction();
  const d = draft.data;

  useEffect(() => { if (!env && runnable.length) setEnv(runnable[0].key); }, [env, runnable]);
  // A link to one case scrolls to it once the cases are on the page.
  useEffect(() => { if (d && hash) document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView?.(); }, [d, hash]);

  const run = async () => {
    const r = await action.run(() => api.runAiDraft(project, id, env));
    if (r) setStarted(r.runId);
  };

  return (
    <>
      <p><Link to="/qa/test-cases">← {tr('Test cases')}</Link> · <Link to="/qa/test-cases/drafts">{tr('Generation history')}</Link></p>
      <ErrorBanner error={draft.error} />
      {d && (
        <Section title={d.collection?.name ?? tr('Invalid draft')} actions={d.valid && <>
          <button className="secondary" onClick={() => download(d)}>{tr('Download')}</button>
          {can('OPERATOR') && (runnable.length ? <>
            <select aria-label={tr('Environment')} value={env} onChange={e => setEnv(e.target.value)}>
              {runnable.map(e => <option key={e.key} value={e.key}>{e.name}</option>)}
            </select>
            <button onClick={() => void run()} disabled={action.busy || !env}>{tr('Run')}</button>
          </> : <span className="muted small">{tr('No environment registered')}</span>)}
        </>}>
          <dl className="meta">
            <dt>{tr('Status')}</dt><dd><span className={`badge ${d.valid ? 'badge-ok' : 'badge-bad'}`}>{d.valid ? tr('{n} cases', { n: d.collection?.cases?.length ?? 0 }) : tr('invalid')}</span>
              {d.collection?.key && <> <code className="muted">{d.collection.key}</code></>}</dd>
            <dt>{tr('Project')}</dt><dd>{d.projectKey}</dd>
            <dt>{tr('OpenAPI file')}</dt><dd><code className="wrap">{d.specPath}</code> @ <code>{d.commitSha.slice(0, 7)}</code></dd>
            <dt>{tr('Service')}</dt><dd>{d.service}</dd>
            {d.instructions && <><dt>{tr('Focus')}</dt><dd>{d.instructions}</dd></>}
            <dt>{tr('Model')}</dt><dd>{d.provider}/{d.model} · {tr('{in} in · {out} out', { in: d.inputTokens.toLocaleString(), out: d.outputTokens.toLocaleString() })}</dd>
            <dt>{tr('Generated')}</dt><dd><Time value={d.createdAt} /> {tr('by {who}', { who: d.createdBy })}</dd>
          </dl>
          <ErrorBanner error={action.error} />
          {started && <p className="small">{tr('Started')} <Link to={`/runs/${started}`}>{tr('the draft\'s run')}</Link>.</p>}
          {d.error && <div className="error">{d.error}</div>}
          {d.notes && <details className="draft-notes"><summary>{tr('Notes from the model')}</summary><p className="notes">{d.notes}</p></details>}
          {d.valid && d.collection?.cases && <>
            <CategoryCounts cases={d.collection.cases} />
            <CasesTable cases={d.collection.cases} />
          </>}
        </Section>
      )}
    </>
  );
}
