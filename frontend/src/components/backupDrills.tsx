import { api } from '../api/client';
import { useAction, usePolling } from '../hooks';
import { Empty, ErrorBanner, Section, Time } from './ui';

const BADGE: Record<string, string> = { RUNNING: 'badge-warn', PASSED: 'badge-ok', FAILED: 'badge-bad', SKIPPED: 'badge-muted' };
const size = (b: number | null) => (b === null ? '—' : b >= 1024 * 1024 ? `${(b / 1024 / 1024).toFixed(1)} MiB` : `${Math.round(b / 1024)} KiB`);

/** Backup-restore drills (P42): each restores a fresh dump into a throwaway database and compares it with the live one. */
export function BackupDrillsPanel() {
  const drills = usePolling(() => api.backupDrills(), [], 5000);
  const action = useAction();
  const running = (drills.data ?? []).some(d => d.status === 'RUNNING');
  const run = async () => { if (await action.run(() => api.runBackupDrill()) !== undefined) void drills.reload(); };
  return (
    <Section title="Backup drills" actions={<button className="secondary" disabled={action.busy || running} onClick={() => void run()}>{running ? 'Drill running…' : 'Run drill now'}</button>}>
      <p className="muted small">Every night (04:15 UTC) EAHP dumps the live database, restores the dump into a throwaway Postgres container and checks the schema version and row counts.
        <code> scripts/restore.sh --drill</code> checks a backup file the same way; <code>scripts/restore.sh --production FILE</code> restores one.</p>
      <ErrorBanner error={drills.error ?? action.error} />
      {drills.data?.length === 0 && <Empty>No drill has run yet.</Empty>}
      {drills.data && drills.data.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>Started</th><th>Result</th><th>Schema</th><th>Dump</th><th>Rows restored</th></tr></thead>
          <tbody>{drills.data.map(d => (
            <tr key={d.id}>
              <td className="small"><Time value={d.startedAt} /><div className="muted">{d.trigger === 'SCHEDULE' ? 'scheduled' : `by ${d.actor}`}</div></td>
              <td><span className={`badge ${BADGE[d.status]}`}>{d.status.toLowerCase()}</span>{d.error && <div className="small error">{d.error}</div>}</td>
              <td className="small">{d.schemaVersion === null ? '—' : `V${d.schemaVersion}`}</td>
              <td className="small">{size(d.dumpBytes)}</td>
              <td className="small">{d.restoredRows ? Object.entries(d.restoredRows).map(([t, n]) => `${t} ${n.toLocaleString()}`).join(' · ') : '—'}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
    </Section>
  );
}
