import { Fragment, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { Empty, ErrorBanner, Section, Status, Time } from '../components/ui';
import { usePolling } from '../hooks';
import type { PullRequestRun } from '../api/types';

const REASONS: Record<string, string> = {
  FORK_NOT_TRUSTED: 'From a fork: not run automatically',
  SUITE_UNAVAILABLE: 'Suite disabled',
};

/** What the GitHub poller saw for each pull request head commit, and what EAHP did about it. */
export function PullRequestsPage() {
  const polls = usePolling(() => api.gitHubPolls(), [], 15000);
  const rows = usePolling(() => api.pullRequestRuns(), [], 5000);
  const [open, setOpen] = useState<string | null>(null);

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
            <thead><tr><th>Pull request</th><th>Commit</th><th>Suite</th><th>Trigger</th><th>Run</th><th>Seen</th><th /></tr></thead>
            <tbody>{rows.data.map(r => (
              <Fragment key={r.id}>
              <tr>
                <td className="wrap-cell"><a href={r.prUrl} target="_blank" rel="noopener noreferrer">{r.projectKey} #{r.prNumber}</a> {r.prTitle}</td>
                <td><code title={r.headSha}>{r.headSha.slice(0, 7)}</code></td>
                <td>{r.suiteKey}</td>
                <td><Status value={r.status} />{r.skipReason && <div className="muted small">{REASONS[r.skipReason] ?? r.skipReason}</div>}</td>
                <td>{r.runId ? <Link to={`/runs/${r.runId}`}><Status value={r.runStatus} /></Link> : <span className="muted">—</span>}
                  {r.runErrorCode && <div className="muted small">{r.runErrorCode}</div>}</td>
                <td><Time value={r.createdAt} /></td>
                <td><button className="secondary" aria-expanded={open === r.id} onClick={() => setOpen(open === r.id ? null : r.id)}>
                  {open === r.id ? 'Hide impact' : 'Impact'}</button></td>
              </tr>
              {open === r.id && <tr><td colSpan={7}><ImpactView row={r} /></td></tr>}
              </Fragment>
            ))}</tbody>
          </table></div>
        )}
      </Section>
    </>
  );
}

const REASON: Record<string, string> = { CHANGED: 'changed', DEPENDS_ON_CHANGED: 'depends on a change', BUILD_CHANGED: 'build changed' };
const ERRORS: Record<string, string> = {
  BASE_UNKNOWN: 'The base commit was not recorded for this pull request (seen before impact analysis existed).',
  REPOSITORY_UNAVAILABLE: 'The repository could not be read. EAHP retries after ten minutes.',
  ANALYSIS_FAILED: 'The analysis failed; see the backend log.',
};

/** What the pull request changes and which modules and tests that puts in scope. Advisory: it does not skip any tests. */
function ImpactView({ row }: { row: PullRequestRun }) {
  const impact = usePolling(() => api.changeImpact(row.id), [row.id], 0);
  if (impact.error) return <ErrorBanner error={impact.error} />;
  if (!impact.data) return <p className="muted small">Analysing the change…</p>;
  const d = impact.data;
  if (!d.impact) return <p className="muted">{ERRORS[d.error ?? ''] ?? d.error}</p>;
  const i = d.impact;
  return (
    <div className="stack-tight wrap-cell">
      <p className="small"><code>{(d.baseSha ?? '').slice(0, 7)}…{d.headSha.slice(0, 7)}</code>: {i.changedFileCount} changed file{i.changedFileCount === 1 ? '' : 's'}
        {i.truncated ? ' (list truncated)' : ''}; {i.modules.length} affected module{i.modules.length === 1 ? '' : 's'}; {i.testClasses.length} test classes in scope.</p>
      {i.modules.length > 0 && (
        <table>
          <thead><tr><th>Module</th><th>Why</th><th>Changed files</th><th>Test classes</th></tr></thead>
          <tbody>{i.modules.map(m => (
            <tr key={m.dir}><td><code>{m.dir}</code></td><td>{REASON[m.reason] ?? m.reason}</td><td>{m.changedFiles}</td>
              <td>{m.testClasses === 0 ? <span className="badge badge-warn">none</span> : m.testClasses}</td></tr>
          ))}</tbody>
        </table>
      )}
      {i.notes.map((n, k) => <p key={k} className="muted small">⚠ {n}</p>)}
      <details><summary className="small">Changed files</summary><ul className="small">{i.changedFiles.map(f => <li key={f}><code>{f}</code></li>)}</ul></details>
    </div>
  );
}
