import { api } from '../api/client';
import type { QaProject } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { ErrorBanner, Time } from './ui';

/** Whether QA results go back to this project's pull requests as commit statuses and an EAHP comment. */
export function GitHubWriteBackPanel({ project }: { project: QaProject }) {
  const { can } = useSession();
  const view = usePolling(() => api.githubWriteBack(project.key), [project.key], 30000);
  const action = useAction();
  const s = view.data?.settings;
  const toggle = async () => { if (s && await action.run(() => api.setGithubWriteBack(project.key, !s.enabled))) void view.reload(); };
  if (!s) return <ErrorBanner error={view.error} />;
  return (
    <>
      <h3 className="subhead">GitHub write-back</h3>
      <p className="small">
        {s.enabled ? 'On' : 'Off'}: {s.enabled ? 'results are posted to pull requests as eahp/<suite> and eahp/release-gate statuses and one EAHP comment.' : 'nothing is written to GitHub.'}
        {!s.tokenConfigured && <span className="muted"> No write token is configured (EAHP_GITHUB_WRITE_TOKEN), so nothing would be sent.</span>}
        {can('APPROVER') && <> <button className="secondary" disabled={action.busy} onClick={() => void toggle()}>{s.enabled ? 'Turn off' : 'Turn on'}</button></>}
      </p>
      {view.data && view.data.recent.length > 0 && (
        <details><summary className="small muted">Recent GitHub calls</summary>
          <table className="small"><tbody>{view.data.recent.map(e => (
            <tr key={e.id}><td>{e.kind}{e.prNumber ? ` #${e.prNumber}` : ''}</td><td><code>{e.commitSha.slice(0, 7)}</code></td><td>{e.status}{e.lastError ? ` · ${e.lastError}` : ''}</td>
              <td><Time value={e.sentAt ?? e.createdAt} /></td></tr>
          ))}</tbody></table>
        </details>
      )}
      <ErrorBanner error={view.error ?? action.error} />
    </>
  );
}
