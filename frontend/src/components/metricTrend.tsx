/** One point of a metric over time; {@code title} is the hover text. */
export interface TrendPoint { key: string; value: number; title: string }

/**
 * A small line chart of one metric, oldest to newest, with an optional limit line, per-point hover text and a
 * table view. Used for performance (p95) and coverage trends.
 */
export function MetricTrend({ label, points, format, limit, limitLabel }: {
  label: string; points: TrendPoint[]; format: (v: number) => string; limit?: number | null; limitLabel?: string;
}) {
  if (points.length === 0) return null;
  const height = 48, step = 16;
  const width = Math.max((points.length - 1) * step, 1);
  const max = Math.max(...points.map(p => p.value), limit ?? 0) || 1;
  const y = (v: number) => height - (v / max) * height;
  const path = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${i * step},${y(p.value).toFixed(1)}`).join(' ');
  return (
    <div className="trend">
      <div className="trend-axis small muted"><span>{format(max)}</span><span>0</span></div>
      <svg width={width + 8} height={height + 1} role="img" aria-label={`${label}, oldest to newest`}>
        <line x1={0} x2={width} y1={height + 0.5} y2={height + 0.5} className="trend-base" />
        {limit != null && <line x1={0} x2={width} y1={y(limit)} y2={y(limit)} className="trend-limit"><title>{limitLabel ?? `limit ${format(limit)}`}</title></line>}
        {points.length > 1 && <path className="trend-line" d={path} />}
        {points.map((p, i) => (
          <g key={p.key} className="trend-hit">
            <rect x={i * step - step / 2} y={0} width={step} height={height} fill="transparent" />
            <circle className="trend-dot" cx={i * step} cy={y(p.value)} r={4} />
            <title>{p.title}</title>
          </g>
        ))}
      </svg>
      <span className="small muted">{label}, oldest to newest</span>
      <details><summary className="small muted">Table</summary>
        <table className="small"><tbody>{points.map(p => <tr key={p.key}><td>{p.title}</td></tr>)}</tbody></table>
      </details>
    </div>
  );
}
