import { api } from '../api/client';
import type { PerformanceLimit, PerformancePoint } from '../api/types';
import { usePolling } from '../hooks';
import { MetricTrend } from './metricTrend';
import { Empty, ErrorBanner, Section, Status, Time } from './ui';
import { useT } from '../i18n';

const ms = (v: number) => (v >= 100 ? `${Math.round(v)} ms` : `${v.toFixed(2)} ms`);
const rate = (v: number | null) => (v === null ? '—' : `${(v * 100).toFixed(2)}%`);

/** Performance suites (P38): p95 latency per measured run with the gate's limit, and the latest numbers. */
export function PerformancePanel({ project, limits }: { project: string; limits: Record<string, PerformanceLimit> }) {
  const tr = useT();
  const points = usePolling(() => api.performance(project), [project], 30000);
  const bySuite = new Map<string, PerformancePoint[]>();
  for (const p of points.data ?? []) bySuite.set(p.suite, [...(bySuite.get(p.suite) ?? []), p]);
  return (
    <Section title={tr('Performance')}>
      <ErrorBanner error={points.error} />
      {points.data?.length === 0 && <Empty>{tr('No performance results yet. Suites report them with a k6 summary file ("performance" in .eahp/qa-suites.json).')}</Empty>}
      {[...bySuite.entries()].map(([suite, list]) => {
        const last = list[list.length - 1];
        const limit = limits[suite];
        return (
          <article key={suite} className="item">
            <div className="row spread">
              <div className="row"><strong>{suite}</strong><Status value={last.outcome} />
                <span className="muted small">{tr('last at')} <code>{last.commit.slice(0, 7)}</code>, <Time value={last.at} /></span></div>
              <span className="small">p95 <strong>{ms(last.p95Ms)}</strong> · errors {rate(last.errorRate)} · {Math.round(last.requestsPerSecond)} req/s · {last.requests.toLocaleString()} requests</span>
            </div>
            <MetricTrend label={tr('p95 latency')} points={list.map(p => ({ key: p.runId, value: p.p95Ms,
              title: `${p.commit.slice(0, 7)} ${new Date(p.at).toISOString().slice(0, 16).replace('T', ' ')}: p95 ${ms(p.p95Ms)}, errors ${rate(p.errorRate)}, ${Math.round(p.requestsPerSecond)} req/s` }))}
              format={ms} limit={limit?.maxP95Ms ?? null} limitLabel={limit?.maxP95Ms != null ? `gate limit ${ms(limit.maxP95Ms)}` : undefined} />
          </article>
        );
      })}
    </Section>
  );
}
