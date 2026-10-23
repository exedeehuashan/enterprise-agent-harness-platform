import { useEffect, useState, type FormEvent } from 'react';
import { api, newKey } from '../api/client';
import type { Artifact, QaProject, Run } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { Empty, ErrorBanner, Section, Status, Time } from './ui';

/** Suites that need Docker access must use the approval-gated tool; the backend enforces the same split. */
export const QA_TOOLS = { normal: 'QA_RUN_SUITE', privileged: 'QA_RUN_PRIVILEGED_SUITE' } as const;
export const isQaTool = (tool: string) => tool === QA_TOOLS.normal || tool === QA_TOOLS.privileged;

/** "online-shop/backend-unit @ 39a6a0b" for QA runs, otherwise the tool name. */
export function runLabel(run: Run): string {
  const a = run.arguments;
  if (!isQaTool(run.tool) || !a) return run.tool;
  return `${String(a.project)}/${String(a.suite)} @ ${String(a.commit ?? '').slice(0, 7)}`;
}

export function RunSuiteForm({ taskId, onStarted }: { taskId: string; onStarted: () => void }) {
  const projects = usePolling(() => api.qaProjects(), [], 0);
  const [projectKey, setProjectKey] = useState('');
  const [suiteKey, setSuiteKey] = useState('');
  const [ref, setRef] = useState('');
  /** The ref whose suites are shown; updated when the ref field loses focus, so typing does not resolve every keystroke. */
  const [checkedRef, setCheckedRef] = useState('');
  const [timeout, setTimeoutSeconds] = useState(1800);
  const action = useAction();

  const enabled = (projects.data ?? []).filter(p => p.enabled);
  const project: QaProject | undefined = enabled.find(p => p.key === projectKey) ?? enabled[0];
  const catalog = usePolling(() => (project ? api.suiteCatalog(project.key, checkedRef || undefined) : Promise.resolve(null)), [project?.key, checkedRef], 0);
  // Isolated suites first, so the default choice never needs host-level Docker access.
  const suites = (catalog.data?.suites ?? []).slice().sort((x, y) => Number(x.dockerAccess) - Number(y.dockerAccess));
  const suite = suites.find(s => s.key === suiteKey) ?? suites[0];

  useEffect(() => { if (suite) setTimeoutSeconds(t => Math.min(Math.max(t, 1), suite.maxTimeoutSeconds)); }, [suite]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!project || !suite) return;
    const tool = suite.dockerAccess ? QA_TOOLS.privileged : QA_TOOLS.normal;
    const args: Record<string, unknown> = { project: project.key, suite: suite.key };
    if (ref.trim()) args.ref = ref.trim();
    if (await action.run(() => api.startRun(taskId, newKey('qa'), tool, timeout, args))) onStarted();
  };

  if (projects.error) return <ErrorBanner error={projects.error} />;
  if (projects.data && enabled.length === 0) return <Empty>No test projects are registered.</Empty>;
  if (!project) return null;
  return (
    <form className="stack" onSubmit={submit}>
      <div className="row">
        <label className="inline">Project
          <select value={project.key} onChange={e => { setProjectKey(e.target.value); setSuiteKey(''); setRef(''); setCheckedRef(''); }}>
            {enabled.map(p => <option key={p.key} value={p.key}>{p.name}</option>)}
          </select>
        </label>
        <label className="inline">Branch, tag or commit
          <input value={ref} onChange={e => setRef(e.target.value)} onBlur={() => setCheckedRef(ref.trim())} placeholder={project.defaultRef} maxLength={100} />
        </label>
        <label className="inline">Suite
          <select value={suite?.key ?? ''} onChange={e => setSuiteKey(e.target.value)} disabled={!suite}>
            {suites.map(s => <option key={s.key} value={s.key}>{s.name}{s.source === 'repository' ? ' [repo]' : ''}{s.dockerAccess ? ' (needs approval)' : ''}</option>)}
          </select>
        </label>
        <label className="inline">Timeout (s)
          <input type="number" min={1} max={suite?.maxTimeoutSeconds ?? 300} value={timeout} onChange={e => setTimeoutSeconds(Number(e.target.value))} />
        </label>
        <button type="submit" disabled={action.busy || !suite || checkedRef !== ref.trim()}>{action.busy ? 'Resolving…' : 'Run suite'}</button>
      </div>
      {catalog.data && (
        <p className="muted small">
          {catalog.data.ref} → <code>{catalog.data.commit.slice(0, 12)}</code> ·{' '}
          {catalog.data.repositoryFile === 'found' && <>suites from <code>{project.suitesFile}</code> in the repository, registry suites as fallback</>}
          {catalog.data.repositoryFile === 'absent' && <>no <code>{project.suitesFile}</code> at this commit; registry suites</>}
          {catalog.data.repositoryFile === 'disabled' && <>registry suites</>}
          {catalog.data.repositoryFile === 'invalid' && <>registry suites only</>}
        </p>
      )}
      {catalog.data?.repositoryError && <div className="error" role="alert"><strong>{project.suitesFile} is invalid at this commit:</strong> {catalog.data.repositoryError}</div>}
      <ErrorBanner error={catalog.error} />
      {suite?.description && <p className="muted small">{suite.description}</p>}
      {suite?.dockerAccess && (
        <p className="muted small">This suite needs Docker access on the runner host, so an approver must approve the exact commit before it runs.</p>
      )}
      <ErrorBanner error={action.error} />
    </form>
  );
}

export function ArtifactList({ runId }: { runId: string }) {
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
    <Section title="Artifacts">
      <ErrorBanner error={artifacts.error ?? action.error} />
      {artifacts.data?.length === 0 && <Empty>No artifacts were produced.</Empty>}
      {artifacts.data && artifacts.data.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>Name</th><th>Size</th><th>Attempt</th><th>SHA-256</th><th /></tr></thead>
          <tbody>{artifacts.data.map(a => (
            <tr key={a.id}>
              <td>{a.name}</td><td>{formatBytes(a.sizeBytes)}</td><td>{a.attempt}</td>
              <td><code title={a.sha256}>{a.sha256.slice(0, 12)}</code></td>
              <td><button className="secondary" onClick={() => download(a)} disabled={action.busy}>Download</button></td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
    </Section>
  );
}

/** Test outcome from the run's summary.json: the evidence a reviewer needs before trusting a result. */
export function QaEvidence({ run }: { run: Run }) {
  const artifacts = usePolling(() => api.artifacts(run.id), [run.id, run.status], 0);
  const summaryId = artifacts.data?.find(a => a.name === 'summary.json')?.id;
  const summary = usePolling(() => (summaryId ? api.qaSummary(summaryId) : Promise.resolve(null)), [summaryId], 0);
  const a = run.arguments ?? {};

  return (
    <Section title="Test result" actions={summary.data && <Status value={summary.data.outcome === 'PASSED' ? 'COMPLETED' : summary.data.outcome === 'STOPPED' ? 'CANCELLED' : 'FAILED'} />}>
      <dl className="meta">
        <dt>Project / suite</dt><dd>{String(a.project)} / {String(a.suite)}</dd>
        <dt>Ref</dt><dd>{String(a.ref)} → <code className="wrap">{String(a.commit)}</code></dd>
      </dl>
      <ErrorBanner error={artifacts.error ?? summary.error} />
      {!summary.data && <p className="muted">{['QUEUED', 'RUNNING', 'WAITING_APPROVAL'].includes(run.status) ? 'Waiting for the suite to finish.' : 'No test summary was produced; see the run error and artifacts.'}</p>}
      {summary.data && (
        <>
          <div className="stats">
            <Stat label="Outcome" value={summary.data.outcome.replace(/_/g, ' ')} tone={summary.data.outcome === 'PASSED' ? 'ok' : 'bad'} />
            <Stat label="Exit code" value={summary.data.exitCode === null ? 'n/a' : String(summary.data.exitCode)} />
            <Stat label="Tests" value={String(summary.data.tests)} />
            <Stat label="Failures" value={String(summary.data.failures)} tone={summary.data.failures ? 'bad' : undefined} />
            <Stat label="Errors" value={String(summary.data.errors)} tone={summary.data.errors ? 'bad' : undefined} />
            <Stat label="Skipped" value={String(summary.data.skipped)} />
            <Stat label="Duration" value={`${summary.data.durationSeconds} s`} />
          </div>
          <dl className="meta">
            <dt>Suite source</dt><dd>{summary.data.suiteSource === 'repository' ? 'declared in the repository at this commit' : 'EAHP registry'}</dd>
            <dt>Started</dt><dd><Time value={summary.data.startedAt} /></dd>
            <dt>Runner</dt><dd><code>{summary.data.runnerImage}</code>{summary.data.dockerAccess ? ' with Docker access' : ''}</dd>
            <dt>Command</dt><dd><code className="wrap">{summary.data.command.join(' ')}</code></dd>
            <dt>Reports</dt><dd>{summary.data.reportFiles} files{summary.data.unreadableReportFiles ? `, ${summary.data.unreadableReportFiles} unreadable` : ''}{summary.data.reportsTruncated ? ', archive omitted (too large)' : ''}</dd>
          </dl>
          {summary.data.failedTests.length > 0 && (
            <>
              <h3 className="subhead">Failing tests</h3>
              <div className="table-wrap"><table>
                <thead><tr><th>Test</th><th>Kind</th><th>Message</th></tr></thead>
                <tbody>{summary.data.failedTests.map((t, i) => (
                  <tr key={i}><td><code>{t.suite}{t.name ? `#${t.name}` : ''}</code></td><td>{t.kind}</td><td className="wrap-cell">{t.message}</td></tr>
                ))}</tbody>
              </table></div>
            </>
          )}
        </>
      )}
    </Section>
  );
}

function Stat({ label, value, tone }: { label: string; value: string; tone?: 'ok' | 'bad' }) {
  return <div className={`stat${tone ? ` stat-${tone}` : ''}`}><span className="muted small">{label}</span><strong>{value}</strong></div>;
}

function formatBytes(n: number) {
  return n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KiB` : `${(n / 1024 / 1024).toFixed(1)} MiB`;
}
