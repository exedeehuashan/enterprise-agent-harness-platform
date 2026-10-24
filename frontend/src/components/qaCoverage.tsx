import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { CoveragePoint, NewCodeCoverage } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { MetricTrend } from './metricTrend';
import { Empty, ErrorBanner, Section, Time } from './ui';
import { useT } from '../i18n';

const pct = (v: number | null) => (v === null ? '—' : `${v}%`);
const STATUS: Record<NewCodeCoverage['status'], string> = {
  COMPUTED: '', NO_RUN: 'The suite has no coverage run at the head commit.', NO_LINE_DATA: 'The run at the head commit kept no line-level coverage.',
  NO_CHANGED_LINES: 'No line that the coverage reports know changed between these commits.',
};

/** Coverage (P39): line coverage per suite over time, and the coverage of the lines a change touches. */
export function CoveragePanel({ project, defaultRef, minimums }: { project: string; defaultRef: string; minimums: Record<string, number> }) {
  const tr = useT();
  const points = usePolling(() => api.coverageTrend(project), [project], 30000);
  const bySuite = new Map<string, CoveragePoint[]>();
  for (const p of points.data ?? []) bySuite.set(p.suite, [...(bySuite.get(p.suite) ?? []), p]);
  const suites = [...bySuite.keys()];
  const [form, setForm] = useState({ base: defaultRef, head: '', suite: '' });
  const [result, setResult] = useState<NewCodeCoverage | null>(null);
  const action = useAction();
  const check = async (e: FormEvent) => {
    e.preventDefault();
    const r = await action.run(() => api.newCodeCoverage(project, form.base.trim(), form.head.trim(), form.suite || suites[0]));
    if (r) setResult(r);
  };
  return (
    <Section title={tr('Coverage')}>
      <ErrorBanner error={points.error ?? action.error} />
      {points.data?.length === 0 && <Empty>{tr('No coverage recorded yet. Suites report it with "coverage" in .eahp/qa-suites.json.')}</Empty>}
      {[...bySuite.entries()].map(([suite, list]) => {
        const last = list[list.length - 1];
        return (
          <article key={suite} className="item">
            <div className="row spread">
              <div className="row"><strong>{suite}</strong><span className="muted small">{tr('last at')} <code>{last.commit.slice(0, 7)}</code>, <Time value={last.at} /></span></div>
              <span className="small">{tr('lines')} <strong>{pct(last.linePercent)}</strong> · branches {pct(last.branchPercent)}</span>
            </div>
            <MetricTrend label={tr('line coverage')} format={v => `${Math.round(v)}%`} points={list.filter(p => p.linePercent !== null).map(p => ({ key: p.runId, value: p.linePercent!,
              title: `${p.commit.slice(0, 7)} ${new Date(p.at).toISOString().slice(0, 16).replace('T', ' ')}: lines ${pct(p.linePercent)}, branches ${pct(p.branchPercent)}` }))} />
          </article>
        );
      })}
      {suites.length > 0 && (
        <form className="row" onSubmit={check}>
          <h3 className="subhead">{tr('New-code coverage')}</h3>
          <label className="inline">{tr('Base')}<input value={form.base} onChange={e => setForm({ ...form, base: e.target.value })} required /></label>
          <label className="inline">{tr('Head')}<input value={form.head} onChange={e => setForm({ ...form, head: e.target.value })} placeholder={tr('branch or commit')} required /></label>
          <label className="inline">{tr('Suite')}<select value={form.suite || suites[0]} onChange={e => setForm({ ...form, suite: e.target.value })}>
            {suites.map(s => <option key={s} value={s}>{s}</option>)}</select></label>
          <button type="submit" className="secondary" disabled={action.busy}>{tr('Check changed lines')}</button>
        </form>
      )}
      {result && (
        <div className="stack-tight small">
          {result.status === 'COMPUTED' ? (
            <p><strong>{pct(result.percent)}</strong> of changed lines covered ({result.covered} of {result.covered + result.missed}) in {result.changedFiles} changed files
              {minimums[result.suite] != null && <> · gate requires {minimums[result.suite]}%</>}</p>
          ) : <p className="muted">{tr(STATUS[result.status])}</p>}
          {result.files.filter(f => f.missed > 0).map(f => (
            <div key={f.path}><code className="wrap">{f.path}</code> {f.covered}/{f.covered + f.missed} covered · uncovered lines {f.uncoveredLines.join(', ')}</div>
          ))}
        </div>
      )}
    </Section>
  );
}
