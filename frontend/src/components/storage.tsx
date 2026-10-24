import { api } from '../api/client';
import { useAction, usePolling } from '../hooks';
import { ErrorBanner, Section, Time } from './ui';
import { useT } from '../i18n';

const size = (n: number) => (n < 1024 * 1024 ? `${(n / 1024).toFixed(0)} KiB` : `${(n / 1024 / 1024).toFixed(1)} MiB`);

/** Storage use and retention for approvers: what is kept, what has expired, and a way to apply retention now. */
export function StoragePanel() {
  const tr = useT();
  const report = usePolling(() => api.storage(), [], 0);
  const action = useAction();
  const run = async () => { if (await action.run(() => api.runRetention())) void report.reload(); };
  const r = report.data;
  return (
    <Section title={tr('Storage and retention')} actions={<button className="secondary" onClick={run} disabled={action.busy}>{tr('Apply retention now')}</button>}>
      <ErrorBanner error={report.error ?? action.error} />
      {r && (
        <>
          <p className="small">Artifact content is kept for {r.artifactDays} days (the latest run per project, suite and ref always keeps its artifacts);
            per-test history for {r.testResultDays} days. Expired artifacts keep their name, size and SHA-256.</p>
          <div className="stats">
            <div className="stat"><span className="muted small">{tr('Live artifacts')}</span><strong>{r.liveArtifacts}</strong><span className="muted small">{size(r.liveArtifactBytes)}</span></div>
            <div className="stat"><span className="muted small">{tr('Expired artifacts')}</span><strong>{r.expiredArtifacts}</strong><span className="muted small">{tr('metadata kept')}</span></div>
            <div className="stat"><span className="muted small">{tr('Last retention')}</span><strong>{r.lastRun ? `${r.lastRun.artifactsExpired} expired` : 'never'}</strong>
              <span className="muted small">{r.lastRun ? <>{size(r.lastRun.bytesFreed)} freed, <Time value={r.lastRun.startedAt} /> by {r.lastRun.actor}</> : tr('runs nightly at 03:30 UTC')}</span></div>
          </div>
          <details><summary className="small muted">{tr('Table sizes')}</summary>
            <table className="small"><tbody>{Object.entries(r.tableBytes).map(([t, b]) => <tr key={t}><td><code>{t}</code></td><td>{size(b)}</td></tr>)}</tbody></table>
          </details>
        </>
      )}
    </Section>
  );
}
