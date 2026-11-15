import { useEffect, useState, type FormEvent } from 'react';
import { api, newKey } from '../api/client';
import type { Artifact, QaProject, QaSummary, Run } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { Empty, ErrorBanner, Section, Status, Time } from './ui';
import { AiAnalysisPanel } from './aiAnalysis';
import { AutoFixPanel } from './autoFix';
import { useT } from '../i18n';

/** Suites that need Docker access must use the approval-gated tool; the backend enforces the same split. */
export const QA_TOOLS = { normal: 'QA_RUN_SUITE', privileged: 'QA_RUN_PRIVILEGED_SUITE', api: 'QA_RUN_API_TESTS' } as const;
export const isQaTool = (tool: string) => tool === QA_TOOLS.normal || tool === QA_TOOLS.privileged || tool === QA_TOOLS.api;

/** "online-shop/backend-unit @ 39a6a0b" for QA runs, otherwise the tool name. */
export function runLabel(run: Run): string {
  const a = run.arguments;
  if (!isQaTool(run.tool) || !a) return run.tool;
  return `${String(a.project)}/${String(a.suite)} @ ${String(a.commit ?? '').slice(0, 7)}`;
}

export function RunSuiteForm({ taskId, onStarted }: { taskId: string; onStarted: () => void }) {
  const tr = useT();
  const projects = usePolling(() => api.qaProjects(), [], 0);
  const [projectKey, setProjectKey] = useState('');
  const [suiteKey, setSuiteKey] = useState('');
  const [ref, setRef] = useState('');
  /** The ref whose suites are shown; updated when the ref field loses focus, so typing does not resolve every keystroke. */
  const [checkedRef, setCheckedRef] = useState('');
  const [timeout, setTimeoutSeconds] = useState(1800);
  const [environmentKey, setEnvironmentKey] = useState('');
  const action = useAction();

  const enabled = (projects.data ?? []).filter(p => p.enabled);
  const project: QaProject | undefined = enabled.find(p => p.key === projectKey) ?? enabled[0];
  const catalog = usePolling(() => (project ? api.suiteCatalog(project.key, checkedRef || undefined) : Promise.resolve(null)), [project?.key, checkedRef], 0);
  // Isolated suites first, so the default choice never needs host-level Docker access.
  const suites = (catalog.data?.suites ?? []).slice().sort((x, y) => Number(x.dockerAccess) - Number(y.dockerAccess));
  const suite = suites.find(s => s.key === suiteKey) ?? suites[0];
  const needsEnvironment = !!suite?.environment && Object.keys(suite.environment).length > 0;
  const environments = usePolling(() => (project && needsEnvironment ? api.environments(project.key) : Promise.resolve([])), [project?.key, needsEnvironment], 0);
  const environment = (environments.data ?? []).filter(e => e.enabled).find(e => e.key === environmentKey) ?? (environments.data ?? []).find(e => e.enabled);

  useEffect(() => { if (suite) setTimeoutSeconds(t => Math.min(Math.max(t, 1), suite.maxTimeoutSeconds)); }, [suite]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!project || !suite) return;
    const tool = suite.dockerAccess ? QA_TOOLS.privileged : QA_TOOLS.normal;
    const args: Record<string, unknown> = { project: project.key, suite: suite.key };
    if (ref.trim()) args.ref = ref.trim();
    if (needsEnvironment) { if (!environment) return; args.environment = environment.key; }
    if (await action.run(() => api.startRun(taskId, newKey('qa'), tool, timeout, args))) onStarted();
  };

  if (projects.error) return <ErrorBanner error={projects.error} />;
  if (projects.data && enabled.length === 0) return <Empty>{tr('No test projects are registered.')}</Empty>;
  if (!project) return null;
  return (
    <form className="stack" onSubmit={submit}>
      <div className="row">
        <label className="inline">{tr('Project')}
          <select value={project.key} onChange={e => { setProjectKey(e.target.value); setSuiteKey(''); setRef(''); setCheckedRef(''); }}>
            {enabled.map(p => <option key={p.key} value={p.key}>{p.name}</option>)}
          </select>
        </label>
        <label className="inline">{tr('Branch, tag or commit')}
          <input value={ref} onChange={e => setRef(e.target.value)} onBlur={() => setCheckedRef(ref.trim())} placeholder={project.defaultRef} maxLength={100} />
        </label>
        <label className="inline">{tr('Suite')}
          <select value={suite?.key ?? ''} onChange={e => setSuiteKey(e.target.value)} disabled={!suite}>
            {suites.map(s => <option key={s.key} value={s.key}>{s.name}{s.source === 'repository' ? ' [repo]' : ''}{s.dockerAccess ? ' (needs approval)' : ''}</option>)}
          </select>
        </label>
        {needsEnvironment && (
          <label className="inline">{tr('Environment')}
            <select value={environment?.key ?? ''} onChange={e => setEnvironmentKey(e.target.value)} disabled={!environment}>
              {(environments.data ?? []).filter(e => e.enabled).map(e => <option key={e.key} value={e.key}>{e.name}</option>)}
            </select>
          </label>
        )}
        <label className="inline">{tr('Timeout (s)')}
          <input type="number" min={1} max={suite?.maxTimeoutSeconds ?? 300} value={timeout} onChange={e => setTimeoutSeconds(Number(e.target.value))} />
        </label>
        <button type="submit" disabled={action.busy || !suite || checkedRef !== ref.trim() || (needsEnvironment && !environment)}>{action.busy ? 'Resolving…' : tr('Run suite')}</button>
      </div>
      {catalog.data && (
        <p className="muted small">
          {catalog.data.ref} → <code>{catalog.data.commit.slice(0, 12)}</code> ·{' '}
          {catalog.data.repositoryFile === 'found' && <>{tr('suites from')} <code>{project.suitesFile}</code> {tr('in the repository, registry suites as fallback')}</>}
          {catalog.data.repositoryFile === 'absent' && <>{tr('no')} <code>{project.suitesFile}</code> at this commit; registry suites</>}
          {catalog.data.repositoryFile === 'disabled' && <>{tr('registry suites')}</>}
          {catalog.data.repositoryFile === 'invalid' && <>{tr('registry suites only')}</>}
        </p>
      )}
      {catalog.data?.repositoryError && <div className="error" role="alert"><strong>{tr('{file} is invalid at this commit:', { file: project.suitesFile })}</strong> {catalog.data.repositoryError}</div>}
      <ErrorBanner error={catalog.error} />
      {suite?.description && <p className="muted small">{suite.description}</p>}
      {suite?.dockerAccess && (
        <p className="muted small">{tr('This suite needs Docker access on the runner host, so an approver must approve the exact commit before it runs.')}</p>
      )}
      <ErrorBanner error={action.error} />
    </form>
  );
}

export function ArtifactList({ runId }: { runId: string }) {
  const tr = useT();
  const artifacts = usePolling(() => api.artifacts(runId), [runId], 5000);
  const action = useAction();
  const download = async (a: Artifact) => {
    const blob = await action.run(() => api.artifactBlob(a.id));
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = a.name;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  return (
    <Section title={tr('Artifacts')}>
      <ErrorBanner error={artifacts.error ?? action.error} />
      {artifacts.data?.length === 0 && <Empty>{tr('No artifacts were produced.')}</Empty>}
      {artifacts.data && artifacts.data.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>{tr('Name')}</th><th>{tr('Size')}</th><th>{tr('Attempt')}</th><th>{tr('SHA-256')}</th><th /></tr></thead>
          <tbody>{artifacts.data.map(a => (
            <tr key={a.id}>
              <td>{a.name}</td><td>{formatBytes(a.sizeBytes)}</td><td>{a.attempt}</td>
              <td><code title={a.sha256}>{a.sha256.slice(0, 12)}</code></td>
              <td>{a.expiredAt
                ? <span className="badge badge-muted" title={`Content removed by retention on ${new Date(a.expiredAt).toLocaleString()}; the SHA-256 still identifies it`}>{tr('expired')}</span>
                : <button className="secondary" onClick={() => download(a)} disabled={action.busy}>{tr('Download')}</button>}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
    </Section>
  );
}

/** Test outcome from the run's summary.json: the evidence a reviewer needs before trusting a result. */
export function QaEvidence({ run }: { run: Run }) {
  const tr = useT();
  const artifacts = usePolling(() => api.artifacts(run.id), [run.id, run.status], 0);
  const summaryId = artifacts.data?.find(a => a.name === 'summary.json')?.id;
  const summary = usePolling(() => (summaryId ? api.qaSummary(summaryId) : Promise.resolve(null)), [summaryId], 0);
  const a = run.arguments ?? {};

  return (
    <>
    <Section title={tr('Test result')} actions={summary.data && <Status value={summary.data.outcome === 'PASSED' ? 'COMPLETED' : summary.data.outcome === 'STOPPED' ? 'CANCELLED' : 'FAILED'} />}>
      <dl className="meta">
        <dt>{tr('Project / suite')}</dt><dd>{String(a.project)} / {String(a.suite)}</dd>
        <dt>{tr('Ref')}</dt><dd className="wrap">{String(a.ref)} → <code className="wrap">{String(a.commit)}</code></dd>
      </dl>
      <ErrorBanner error={artifacts.error ?? summary.error} />
      {!summary.data && <p className="muted">{['QUEUED', 'RUNNING', 'WAITING_APPROVAL'].includes(run.status) ? tr('Waiting for the suite to finish.') : tr('No test summary was produced; see the run error and artifacts.')}</p>}
      {summary.data && (
        <>
          <div className="stats">
            <Stat label={tr('Outcome')} value={summary.data.outcome.replace(/_/g, ' ')} tone={summary.data.outcome === 'PASSED' ? 'ok' : 'bad'} />
            <Stat label={tr('Exit code')} value={summary.data.exitCode === null ? 'n/a' : String(summary.data.exitCode)} />
            <Stat label={tr('Tests')} value={String(summary.data.tests)} />
            <Stat label={tr('Failures')} value={String(summary.data.failures)} tone={summary.data.failures ? 'bad' : undefined} />
            <Stat label={tr('Errors')} value={String(summary.data.errors)} tone={summary.data.errors ? 'bad' : undefined} />
            <Stat label={tr('Skipped')} value={String(summary.data.skipped)} />
            <Stat label={tr('Duration')} value={`${summary.data.durationSeconds} s`} />
          </div>
          <dl className="meta">
            <dt>{tr('Suite source')}</dt><dd>{summary.data.suiteSource === 'repository' ? tr('declared in the repository at this commit') : summary.data.suiteSource === 'api-tests' ? tr('API tests declared in the repository at this commit') : tr('EAHP registry')}</dd>
            <dt>{tr('Started')}</dt><dd><Time value={summary.data.startedAt} /></dd>
            {summary.data.runnerImage && <><dt>{tr('Runner')}</dt><dd><code>{summary.data.runnerImage}</code>{summary.data.dockerAccess ? tr(' with Docker access') : ''}</dd></>}
            {summary.data.command && <><dt>{tr('Command')}</dt><dd><code className="wrap">{summary.data.command.join(' ')}</code></dd></>}
            {summary.data.environment && <><dt>{tr('Environment')}</dt><dd>{summary.data.environment}</dd></>}
            <dt>{tr('Reports')}</dt><dd>{tr('{n} files', { n: summary.data.reportFiles })}{summary.data.unreadableReportFiles ? tr(', {n} unreadable', { n: summary.data.unreadableReportFiles }) : ''}{summary.data.reportsTruncated ? tr(', archive omitted (too large)') : ''}</dd>
          </dl>
          <TriageView run={run} fallback={summary.data.failedTests} />
        </>
      )}
    </Section>
    {summary.data && summary.data.outcome !== 'PASSED' && summary.data.outcome !== 'STOPPED' && <AiAnalysisPanel runId={run.id} />}
    {summary.data && (summary.data.outcome === 'TESTS_FAILED' || summary.data.outcome === 'BUILD_FAILED') && (run.tool === 'QA_RUN_SUITE' || run.tool === 'QA_RUN_PRIVILEGED_SUITE')
      && <AutoFixPanel runId={run.id} project={summary.data.project} />}
    </>
  );
}

const HISTORY: Record<string, { text: string; tone: string }> = {
  NEW_FAILURE: { text: 'new failure', tone: 'bad' },
  PERSISTENT: { text: 'already failing', tone: 'warn' },
  FLAKY: { text: 'flaky', tone: 'warn' },
  FIRST_SEEN: { text: 'first run', tone: 'muted' },
};

/** Why the run failed, from the ingested test results: a category per failure and each test's history in this suite. */
function TriageView({ run, fallback }: { run: Run; fallback: QaSummary['failedTests'] }) {
  const tr = useT();
  const triage = usePolling(() => api.qaTriage(run.id), [run.id, run.status], 5000);
  const t = triage.data;
  if (triage.error) return <ErrorBanner error={triage.error} />;
  if (!t || !t.ingested) {
    return (
      <>
        {fallback.length > 0 && <FailedList rows={fallback.map(f => ({ test: `${f.suite}${f.name ? `#${f.name}` : ''}`, message: f.message }))} />}
        {['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(run.status) && <p className="muted small">{tr('Analysing test results…')}</p>}
      </>
    );
  }
  return (
    <>
      {t.category && (
        <div className="triage">
          <strong>{tr('Why it failed: {category}', { category: tr(t.category.replace(/_/g, ' ').toLowerCase()) })}</strong>
          <p>{t.explanation}</p>
        </div>
      )}
      {t.failures.length > 0 && (
        <>
          <h3 className="subhead">{tr('Failing tests')}</h3>
          <div className="table-wrap"><table>
            <thead><tr><th>{tr('Test')}</th><th>{tr('Category')}</th><th>{tr('History')}</th><th>{tr('Message')}</th></tr></thead>
            <tbody>{t.failures.map((f, i) => {
              const h = HISTORY[f.history.label];
              return (
                <tr key={i}>
                  <td><code>{f.testClass}{f.testName ? `#${f.testName}` : ''}</code>{f.failureType && <div className="muted small">{f.failureType}</div>}</td>
                  <td title={f.explanation ?? ''}>{tr(f.category.replace(/_/g, ' ').toLowerCase())}</td>
                  <td><span className={`badge badge-${h?.tone ?? 'muted'}`}>{h ? tr(h.text) : f.history.label}</span>
                    <div className="muted small">{tr('{failed}/{runs} recent runs failed', { failed: f.history.recentFailures, runs: f.history.recentRuns })}
                      {f.history.lastPassedCommit ? <>; {tr('last passed at')} <code>{f.history.lastPassedCommit.slice(0, 7)}</code></> : ''}</div></td>
                  <td className="wrap-cell">{f.message}</td>
                </tr>
              );
            })}</tbody>
          </table></div>
        </>
      )}
    </>
  );
}

function FailedList({ rows }: { rows: { test: string; message: string }[] }) {
  const tr = useT();
  return (
    <>
      <h3 className="subhead">{tr('Failing tests')}</h3>
      <div className="table-wrap"><table>
        <thead><tr><th>{tr('Test')}</th><th>{tr('Message')}</th></tr></thead>
        <tbody>{rows.map((r, i) => <tr key={i}><td><code>{r.test}</code></td><td className="wrap-cell">{r.message}</td></tr>)}</tbody>
      </table></div>
    </>
  );
}

function Stat({ label, value, tone }: { label: string; value: string; tone?: 'ok' | 'bad' }) {
  return <div className={`stat${tone ? ` stat-${tone}` : ''}`}><span className="muted small">{label}</span><strong>{value}</strong></div>;
}

function formatBytes(n: number) {
  return n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KiB` : `${(n / 1024 / 1024).toFixed(1)} MiB`;
}
