import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { Gate, QaReport, QualityDashboard } from '../api/types';
import { CompareCommits } from '../components/qaCompare';
import { PerformancePanel } from '../components/qaPerformance';
import { CoveragePanel } from '../components/qaCoverage';
import { AutoFixesSection } from '../components/autoFix';
import { QuarantinePanel, testLabel as test } from '../components/qaQuarantines';
import { Empty, ErrorBanner, Section, Status, Time } from '../components/ui';
import { useAction, usePolling } from '../hooks';

const percent = (v: number | null) => (v === null ? '—' : `${v}%`);
const label = (s: string | null) => (s ? s.replace(/_/g, ' ').toLowerCase() : '—');

/** Release readiness for one project: gate verdicts, pass-rate trend, failing, flaky and slow tests. */
export function QualityPage() {
  const projects = usePolling(() => api.qaProjects(), [], 0);
  const [projectKey, setProjectKey] = useState('');
  const [days, setDays] = useState(14);
  const project = (projects.data ?? []).find(p => p.key === projectKey) ?? projects.data?.[0];
  const dashboard = usePolling(() => (project ? api.qualityDashboard(project.key, days) : Promise.resolve(null)), [project?.key, days], 15000);

  if (projects.error) return <ErrorBanner error={projects.error} />;
  if (projects.data?.length === 0) return <Empty>No test projects are registered.</Empty>;
  if (!project) return null;
  const d = dashboard.data;
  return (
    <>
      <Section title="Quality" actions={
        <div className="row">
          <label className="inline">Project
            <select value={project.key} onChange={e => setProjectKey(e.target.value)}>
              {(projects.data ?? []).map(p => <option key={p.key} value={p.key}>{p.name}</option>)}
            </select>
          </label>
          <label className="inline">Period
            <select value={days} onChange={e => setDays(Number(e.target.value))}>
              {[7, 14, 30, 90].map(n => <option key={n} value={n}>last {n} days</option>)}
            </select>
          </label>
        </div>
      }>
        <ErrorBanner error={dashboard.error} />
        {d && (
          <div className="stats">
            <Tile label="Pass rate" value={percent(d.passRate)} detail={`${d.passed} of ${d.runs} runs`} />
            <Tile label="Failing tests" value={String(d.failingNow.length)} detail="in each suite's latest run" />
            <Tile label="Flaky tests" value={String(d.flaky.length)} detail="passed and failed on one commit" />
            <Tile label="Gate" value={d.gate ? d.gate.requiredSuites.join(', ') : 'not configured'} detail="required suites" />
          </div>
        )}
      </Section>

      <GateCheck project={project.key} defaultRef={project.defaultRef} />
      <CompareCommits project={project.key} defaultBase={project.defaultRef} />

      {d && (
        <>
          <Section title="Suites">
            {d.suites.length === 0 && <Empty>No finished runs in this period.</Empty>}
            {d.suites.map(s => (
              <article key={s.suite} className="item">
                <div className="row spread">
                  <div className="row"><strong>{s.suite}</strong><Status value={s.lastOutcome} />
                    <span className="muted small">last at <code>{s.lastCommit.slice(0, 7)}</code>, <Time value={s.lastAt} /></span></div>
                  <span className="muted small">{percent(s.passRate)} of {s.runs} runs passed</span>
                </div>
                <PassRateTrend suite={s.suite} daily={s.daily} />
              </article>
            ))}
          </Section>
          <AutoFixesSection project={project.key} />
          <CoveragePanel project={project.key} defaultRef={project.defaultRef} minimums={d.gate?.minNewCodeCoverage ?? {}} />
          <PerformancePanel project={project.key} limits={d.gate?.performance ?? {}} />
          <Section title="Failing now">
            {d.failingNow.length === 0 ? <Empty>No test is currently failing.</Empty> : (
              <div className="table-wrap"><table>
                <thead><tr><th>Test</th><th>Suite</th><th>Cause</th><th>Failing for</th><th>Since</th></tr></thead>
                <tbody>{d.failingNow.map((f, i) => (
                  <tr key={i}><td className="wrap-cell"><code title={`${f.testClass}#${f.testName}`}>{test(f.testClass, f.testName)}</code></td><td>{f.suite}</td><td>{label(f.category)}</td>
                    <td>{f.consecutiveFailures} run{f.consecutiveFailures === 1 ? '' : 's'}</td>
                    <td>{f.failingSince && <code>{f.failingSince.slice(0, 7)}</code>} <Time value={f.failingSinceAt} /></td></tr>
                ))}</tbody>
              </table></div>
            )}
          </Section>
          <QuarantinePanel project={project.key} failing={d.failingNow} />
          <div className="grid-2">
            <Section title="Flaky tests">
              {d.flaky.length === 0 ? <Empty>No flaky tests in this period.</Empty> : (
                <div className="table-wrap"><table><thead><tr><th>Test</th><th>Commits with mixed results</th></tr></thead>
                  <tbody>{d.flaky.map((f, i) => <tr key={i}><td className="wrap-cell"><code>{test(f.testClass, f.testName)}</code></td><td>{f.flakyCommits}</td></tr>)}</tbody></table></div>
              )}
            </Section>
            <Section title="Slowest tests">
              {d.slowest.length === 0 ? <Empty>No timing data yet.</Empty> : (
                <div className="table-wrap"><table><thead><tr><th>Test</th><th>Average</th></tr></thead>
                  <tbody>{d.slowest.map((t, i) => <tr key={i}><td className="wrap-cell"><code>{test(t.testClass, t.testName)}</code></td><td>{(t.averageMs / 1000).toFixed(2)} s</td></tr>)}</tbody></table></div>
              )}
            </Section>
          </div>
          <Section title="Pull requests">
            {d.pullRequests.length === 0 ? <Empty>No pull requests seen yet.</Empty> : (
              <div className="table-wrap"><table>
                <thead><tr><th>Pull request</th><th>Head</th><th>Gate</th><th>Affected modules</th><th>Impact warnings</th></tr></thead>
                <tbody>{d.pullRequests.map(p => (
                  <tr key={p.prNumber}><td className="wrap-cell"><a href={p.url} target="_blank" rel="noopener noreferrer">#{p.prNumber}</a> {p.title}</td>
                    <td><code>{p.headSha.slice(0, 7)}</code></td><td><Status value={p.gate} /></td>
                    <td>{p.affectedModules < 0 ? '—' : p.affectedModules}</td><td>{p.impactNotes < 0 ? '—' : p.impactNotes}</td></tr>
                ))}</tbody>
              </table></div>
            )}
          </Section>
        </>
      )}
    </>
  );
}

function Tile({ label: name, value, detail }: { label: string; value: string; detail: string }) {
  return <div className="stat"><span className="muted small">{name}</span><strong>{value}</strong><span className="muted small">{detail}</span></div>;
}

/**
 * Daily pass rate as thin bars on a shared 0–100% baseline: one series in one hue, so no legend is needed; each bar
 * has a hover tooltip, and the same numbers are available as a table.
 */
function PassRateTrend({ suite, daily }: { suite: string; daily: QualityDashboard['suites'][number]['daily'] }) {
  const height = 48, bar = 10, gap = 4;
  const width = Math.max(daily.length * (bar + gap), bar);
  return (
    <div className="trend">
      <div className="trend-axis small muted"><span>100%</span><span>0%</span></div>
      <svg width={width} height={height + 1} role="img" aria-label={`${suite} daily pass rate`}>
        <line x1={0} x2={width} y1={0.5} y2={0.5} className="trend-ref" />
        <line x1={0} x2={width} y1={height + 0.5} y2={height + 0.5} className="trend-base" />
        {daily.map((d, i) => {
          const rate = d.runs ? d.passed / d.runs : 0;
          const h = Math.max(rate * height, d.runs ? 2 : 0);
          const x = i * (bar + gap);
          return (
            <g key={d.day} className="trend-hit">
              <rect x={x - gap / 2} y={0} width={bar + gap} height={height} fill="transparent" />
              <path className="trend-bar" d={`M${x},${height} V${height - h + 2} q0,-2 2,-2 h${bar - 4} q2,0 2,2 V${height} Z`} />
              <title>{`${d.day}: ${d.passed} of ${d.runs} runs passed (${Math.round(rate * 100)}%)`}</title>
            </g>
          );
        })}
      </svg>
      <span className="small muted">daily pass rate, oldest to newest</span>
      <details><summary className="small muted">Table</summary>
        <table className="small"><thead><tr><th>Day</th><th>Runs</th><th>Passed</th></tr></thead>
          <tbody>{daily.map(d => <tr key={d.day}><td>{d.day}</td><td>{d.runs}</td><td>{d.passed}</td></tr>)}</tbody></table>
      </details>
    </div>
  );
}

function GateCheck({ project, defaultRef }: { project: string; defaultRef: string }) {
  const [ref, setRef] = useState(defaultRef);
  const [gate, setGate] = useState<Gate | null>(null);
  const [report, setReport] = useState<QaReport | null>(null);
  const [copied, setCopied] = useState(false);
  const action = useAction();
  const check = async (e: FormEvent) => {
    e.preventDefault();
    setReport(null);
    const result = await action.run(() => api.qualityGate(project, ref.trim()));
    if (result) setGate(result);
  };
  const showReport = async () => {
    setCopied(false);
    const result = await action.run(() => api.qaReport(project, gate?.commit ?? ref.trim()));
    if (result) setReport(result);
  };
  const copy = async () => {
    if (!report) return;
    try { await navigator.clipboard.writeText(report.markdown); setCopied(true); } catch { setCopied(false); }
  };
  return (
    <Section title="Release gate">
      <form className="row" onSubmit={check}>
        <label className="inline">Branch, tag or commit<input value={ref} onChange={e => setRef(e.target.value)} maxLength={100} /></label>
        <button type="submit" disabled={action.busy || !ref.trim()}>{action.busy ? 'Checking…' : 'Check gate'}</button>
      </form>
      <ErrorBanner error={action.error} />
      {gate && (
        <div className="stack-tight">
          <div className="row"><Status value={gate.verdict} /><code>{gate.commit.slice(0, 12)}</code>
            <span className="muted small">{gate.verdict === 'PASS' ? 'All required suites passed at this commit.' : ''}</span></div>
          {gate.reasons.map((r, i) => <p key={i} className="small">{r}</p>)}
          {gate.notes.map((n, i) => <p key={i} className="muted small">{n}</p>)}
          <div className="row">
            <button type="button" className="secondary" disabled={action.busy} onClick={() => void showReport()}>QA report</button>
            {report && <button type="button" className="secondary" onClick={() => void copy()}>{copied ? 'Copied' : 'Copy Markdown'}</button>}
          </div>
          {report && <pre className="report" aria-label="QA report Markdown">{report.markdown}</pre>}
        </div>
      )}
    </Section>
  );
}
