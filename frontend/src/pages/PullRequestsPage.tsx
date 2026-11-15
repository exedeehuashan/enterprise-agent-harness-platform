import { Fragment, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { Empty, ErrorBanner, Section, Status, Time } from '../components/ui';
import { usePolling } from '../hooks';
import type { PullRequestRun } from '../api/types';
import { useT } from '../i18n';

const REASONS: Record<string, string> = {
  FORK_NOT_TRUSTED: 'From a fork: not run automatically',
  SUITE_UNAVAILABLE: 'Suite disabled',
  NOT_AFFECTED: 'Change does not affect this suite: not needed',
};

/** Why a selective suite still ran in full. */
const FULL_REASONS: Record<string, string> = {
  BUILD_CHANGED: 'build files changed', IMPACT_UNAVAILABLE: 'impact unavailable', TOO_MANY_CHANGES: 'too many changes',
  TOO_MANY_MODULES: 'too many modules', ROOT_MODULE: 'root module changed', NOT_MAVEN: 'not a Maven build', NOT_GRADLE: 'not a Gradle build', NOT_WORKSPACES: 'no workspaces', PATHS_CHANGED: 'its paths changed',
};

function Scope({ row }: { row: PullRequestRun }) {
  const tr = useT();
  if (row.selection === 'MODULES' && row.selectedModules?.length) {
    const n = row.selectedModules.length;
    return <div className="muted small" title={row.selectedModules.join('\n')}>{tr(n === 1 ? '{n} affected module only' : '{n} affected modules only', { n })}</div>;
  }
  if (row.selection === 'FULL' && row.selectionReason && FULL_REASONS[row.selectionReason]) {
    return <div className="muted small">{tr('full suite: {reason}', { reason: tr(FULL_REASONS[row.selectionReason]) })}</div>;
  }
  return null;
}

/** The impact analysis' notes come from the server in English; each known shape is translated with its values. */
function noteText(tr: (key: string, values?: Record<string, string | number>) => string, note: string) {
  let m = note.match(/^(\d+) changed file\(s\) are outside the (Maven|build) modules \(for example frontend, CI or docs\); backend suites do not cover them\.$/);
  if (m) return tr(m[2] === 'Maven' ? '{n} changed file(s) are outside the Maven modules (for example frontend, CI or docs); backend suites do not cover them.'
    : '{n} changed file(s) are outside the build modules (for example frontend, CI or docs); backend suites do not cover them.', { n: m[1] });
  m = note.match(/^(.+) changed but has no test classes of its own; it is only covered through dependent modules, if at all\.$/);
  if (m) return tr('{dir} changed but has no test classes of its own; it is only covered through dependent modules, if at all.', { dir: m[1] });
  m = note.match(/^(.+) changed but has no tests of its own; it is only covered through dependent modules, if at all\.$/);
  if (m) return tr('{dir} changed but has no tests of its own; it is only covered through dependent modules, if at all.', { dir: m[1] === 'The root project' ? tr('The root project') : m[1] });
  m = note.match(/^A build-wide (Gradle|workspace) file changed, so every (\w+) module is affected\.$/);
  if (m) return tr('A build-wide {kind} file changed, so every {system} module is affected.', { kind: m[1] === 'Gradle' ? 'Gradle' : tr('workspace'), system: m[2] });
  return tr(note);
}

/** What the GitHub poller saw for each pull request head commit, and what EAHP did about it. */
export function PullRequestsPage() {
  const tr = useT();
  const polls = usePolling(() => api.gitHubPolls(), [], 15000);
  const rows = usePolling(() => api.pullRequestRuns(), [], 5000);
  const [open, setOpen] = useState<string | null>(null);

  return (
    <>
      <Section title={tr('GitHub polling')}>
        <ErrorBanner error={polls.error} />
        {polls.data?.length === 0 && <Empty>{tr('No project has been polled yet. Polling is enabled with EAHP_GITHUB_POLL_ENABLED.')}</Empty>}
        {polls.data?.map(p => (
          <div key={p.projectKey} className="row spread item">
            <div className="row"><strong>{p.projectKey}</strong>
              {p.lastError ? <span className="badge badge-bad">{tr('error')}</span> : <span className="badge badge-ok">{tr('ok')}</span>}
              {p.openPullRequests !== null && <span className="muted small">{tr('{n} open pull requests', { n: p.openPullRequests })}</span>}</div>
            <span className="muted small">{tr('last poll')} <Time value={p.lastPolledAt} />{p.lastError ? ` · ${p.lastError}` : ''}</span>
          </div>
        ))}
      </Section>
      <Section title={tr('Pull request runs')}>
        <p className="muted small">{tr('Each new commit of a pull request from the same repository runs the project\'s trigger suites; a newer push supersedes the previous run. Module-aware suites run only the affected modules, and path suites run only when their files change. Open View results for a run\'s tests, failure categories and history; with GitHub write-back on, results also appear on the pull request.')}</p>
        <ErrorBanner error={rows.error} />
        {rows.data?.length === 0 && <Empty>{tr('No pull requests have been seen yet.')}</Empty>}
        {rows.data && rows.data.length > 0 && (
          <div className="table-wrap"><table className="pr-runs">
            <colgroup><col className="c-pr" /><col className="c-commit" /><col className="c-suite" /><col /><col className="c-run" /><col className="c-seen" /><col className="c-action" /></colgroup>
            <thead><tr><th>{tr('Pull request')}</th><th>{tr('Commit')}</th><th>{tr('Suite')}</th><th>{tr('Trigger')}</th><th>{tr('Run')}</th><th>{tr('Seen')}</th><th /></tr></thead>
            <tbody>{rows.data.map(r => (
              <Fragment key={r.id}>
              <tr>
                <td className="wrap-cell"><a href={r.prUrl} target="_blank" rel="noopener noreferrer">{r.projectKey} #{r.prNumber}</a> {r.prTitle}</td>
                <td><code title={r.headSha}>{r.headSha.slice(0, 7)}</code></td>
                <td>{r.suiteKey}</td>
                <td><Status value={r.status} />{r.skipReason && <div className="muted small">{REASONS[r.skipReason] ? tr(REASONS[r.skipReason]) : r.skipReason}</div>}<Scope row={r} /></td>
                <td>{r.runId ? <Link to={`/runs/${r.runId}`}><Status value={r.runStatus} /></Link> : <span className="muted">—</span>}
                  {r.runErrorCode && <div className="muted small">{r.runErrorCode}</div>}</td>
                <td><Time value={r.createdAt} /></td>
                <td><div className="row-actions">
                  {r.runId && <Link className="button" to={`/runs/${r.runId}`}>{tr('View results')}</Link>}
                  <button className="secondary" aria-expanded={open === r.id} onClick={() => setOpen(open === r.id ? null : r.id)}>
                    {open === r.id ? tr('Hide impact') : tr('Impact')}</button>
                </div></td>
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
  const tr = useT();
  const impact = usePolling(() => api.changeImpact(row.id), [row.id], 0);
  if (impact.error) return <ErrorBanner error={impact.error} />;
  if (!impact.data) return <p className="muted small">{tr('Analysing the change…')}</p>;
  const d = impact.data;
  if (!d.impact) return <p className="muted">{ERRORS[d.error ?? ''] ? tr(ERRORS[d.error ?? '']) : d.error}</p>;
  const i = d.impact;
  return (
    <div className="stack-tight wrap-cell">
      <p className="small"><code>{(d.baseSha ?? '').slice(0, 7)}…{d.headSha.slice(0, 7)}</code>: {tr(i.changedFileCount === 1 ? '{n} changed file' : '{n} changed files', { n: i.changedFileCount })}
        {i.truncated ? tr(' (list truncated)') : ''}; {tr(i.modules.length === 1 ? '{n} affected module' : '{n} affected modules', { n: i.modules.length })}; {tr('{n} test classes in scope.', { n: i.testClasses.length })}</p>
      {i.modules.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>{tr('Module')}</th><th>{tr('Build')}</th><th>{tr('Why')}</th><th>{tr('Changed files')}</th><th>{tr('Test classes')}</th></tr></thead>
          <tbody>{i.modules.map(m => (
            <tr key={(m.buildSystem ?? 'maven') + m.dir}><td><code>{m.dir || '/'}</code>{m.coordinates && m.coordinates !== m.dir && <div className="muted small">{m.coordinates}</div>}</td>
              <td className="small">{m.buildSystem ?? 'maven'}</td><td>{REASON[m.reason] ? tr(REASON[m.reason]) : m.reason}</td><td>{m.changedFiles}</td>
              <td>{m.testClasses === 0 ? <span className="badge badge-warn">{tr('none')}</span> : m.testClasses}</td></tr>
          ))}</tbody>
        </table></div>
      )}
      {i.notes.map((n, k) => <p key={k} className="muted small">⚠ {noteText(tr, n)}</p>)}
      <details><summary className="small">{tr('Changed files')}</summary><ul className="small">{i.changedFiles.map(f => <li key={f}><code>{f}</code></li>)}</ul></details>
    </div>
  );
}
