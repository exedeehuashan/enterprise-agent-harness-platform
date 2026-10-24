import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { QaProject } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { ErrorBanner, Time } from './ui';

const STATUS_BADGE: Record<string, string> = { STARTING: 'badge-warn', RUNNING: 'badge-ok', STOPPED: 'badge-muted', FAILED: 'badge-bad' };

/**
 * Per-commit environments (P36): the templates in the repository's .eahp/environments.json, runs that start a fresh
 * stack (approval required), and the temporary environments with their expiry.
 */
export function TemporaryEnvironmentsPanel({ project }: { project: QaProject }) {
  const { can } = useSession();
  const templates = usePolling(() => api.environmentTemplates(project.key), [project.key], 0);
  const live = usePolling(() => api.temporaryEnvironments(project.key), [project.key], 15000);
  const action = useAction();
  const [started, setStarted] = useState<string | null>(null);
  const start = async (template: string, mode: 'test' | 'start') => {
    const r = await action.run(() => api.startEnvironmentRun(project.key, template, mode));
    if (r) setStarted(r.runId);
  };
  const stop = async (id: string) => { if (await action.run(() => api.stopTemporaryEnvironment(id)) !== undefined) void live.reload(); };
  const t = templates.data;
  return (
    <>
      <h3 className="subhead">Per-commit environments</h3>
      <ErrorBanner error={templates.error ?? live.error ?? action.error} />
      {t && t.file !== 'found' && (
        <p className="muted small">
          {t.file === 'absent' && <>No <code>.eahp/environments.json</code> at {t.ref}.</>}
          {t.file === 'disabled' && 'This project does not allow repository-declared environments.'}
          {t.file === 'invalid' && <span className="error">{t.error}</span>}
        </p>
      )}
      {t?.templates.map(tpl => (
        <div key={tpl.key} className="row spread item">
          <div><strong>{tpl.name}</strong> <code className="muted small">{tpl.key}</code>
            <div className="muted small">{tpl.compose.join(', ')} · services {tpl.services.map(s => s.name).join(', ')} · tests {tpl.apiTests.join(', ') || 'none'} · lives {tpl.ttlMinutes} min</div>
          </div>
          {can('OPERATOR') && <div className="row">
            <button className="secondary" disabled={action.busy || tpl.apiTests.length === 0} onClick={() => void start(tpl.key, 'test')}>Run tests on a fresh environment</button>
            <button className="secondary" disabled={action.busy} onClick={() => void start(tpl.key, 'start')}>Start environment</button>
          </div>}
        </div>
      ))}
      {started && <p className="small">Started <Link to={`/runs/${started}`}>a run</Link>; it waits for an approver.</p>}
      {(live.data?.length ?? 0) > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>Environment</th><th>Commit</th><th>Status</th><th>Expires / stopped</th><th /></tr></thead>
          <tbody>{live.data!.map(e => (
            <tr key={e.id}>
              <td><code>{e.environmentKey}</code><div className="muted small">{e.template}{e.runId && <> · <Link to={`/runs/${e.runId}`}>run</Link></>}</div></td>
              <td><code>{e.commit.slice(0, 7)}</code></td>
              <td><span className={`badge ${STATUS_BADGE[e.status]}`}>{e.status.toLowerCase()}</span>{e.error && <div className="error small">{e.error}</div>}</td>
              <td className="small"><Time value={e.stoppedAt ?? e.expiresAt} /></td>
              <td>{can('OPERATOR') && (e.status === 'RUNNING' || e.status === 'STARTING') &&
                <button className="danger" disabled={action.busy} onClick={() => window.confirm(`Stop and remove ${e.environmentKey}?`) && void stop(e.id)}>Stop</button>}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
    </>
  );
}
