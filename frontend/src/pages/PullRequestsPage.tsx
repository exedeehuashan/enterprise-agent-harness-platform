import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { Empty, ErrorBanner, Section, Status, Time } from '../components/ui';
import { usePolling } from '../hooks';

const REASONS: Record<string, string> = {
  FORK_NOT_TRUSTED: 'From a fork: not run automatically',
  SUITE_UNAVAILABLE: 'Suite disabled',
};

/** What the GitHub poller saw for each pull request head commit, and what EAHP did about it. */
export function PullRequestsPage() {
  const polls = usePolling(() => api.gitHubPolls(), [], 15000);
  const rows = usePolling(() => api.pullRequestRuns(), [], 5000);

  return (
    <>
      <Section title="GitHub polling">
        <ErrorBanner error={polls.error} />
        {polls.data?.length === 0 && <Empty>No project has been polled yet. Polling is enabled with EAHP_GITHUB_POLL_ENABLED.</Empty>}
        {polls.data?.map(p => (
          <div key={p.projectKey} className="row spread item">
            <div className="row"><strong>{p.projectKey}</strong>
              {p.lastError ? <span className="badge badge-bad">error</span> : <span className="badge badge-ok">ok</span>}
              {p.openPullRequests !== null && <span className="muted small">{p.openPullRequests} open pull requests</span>}</div>
            <span className="muted small">last poll <Time value={p.lastPolledAt} />{p.lastError ? ` · ${p.lastError}` : ''}</span>
          </div>
        ))}
      </Section>
      <Section title="Pull request runs">
        <p className="muted small">Each new head commit of a same-repository pull request runs the project's enabled trigger suites. A newer push supersedes the previous commit's run. Results are not posted back to GitHub.</p>
        <ErrorBanner error={rows.error} />
        {rows.data?.length === 0 && <Empty>No pull requests have been seen yet.</Empty>}
        {rows.data && rows.data.length > 0 && (
          <div className="table-wrap"><table>
            <thead><tr><th>Pull request</th><th>Commit</th><th>Suite</th><th>Trigger</th><th>Run</th><th>Seen</th></tr></thead>
            <tbody>{rows.data.map(r => (
              <tr key={r.id}>
                <td className="wrap-cell"><a href={r.prUrl} target="_blank" rel="noopener noreferrer">{r.projectKey} #{r.prNumber}</a> {r.prTitle}</td>
                <td><code title={r.headSha}>{r.headSha.slice(0, 7)}</code></td>
                <td>{r.suiteKey}</td>
                <td><Status value={r.status} />{r.skipReason && <div className="muted small">{REASONS[r.skipReason] ?? r.skipReason}</div>}</td>
                <td>{r.runId ? <Link to={`/runs/${r.runId}`}><Status value={r.runStatus} /></Link> : <span className="muted">—</span>}
                  {r.runErrorCode && <div className="muted small">{r.runErrorCode}</div>}</td>
                <td><Time value={r.createdAt} /></td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </Section>
    </>
  );
}
