import { useEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { api } from '../api/client';
import { Empty, ErrorBanner, Section } from '../components/ui';
import { usePolling } from '../hooks';
import { useSession } from '../session';
import { ProjectAdminPanel } from '../components/qaAdmin';
import { ProjectSchedules } from '../components/qaSchedules';
import { ProjectEnvironments } from '../components/qaApiTests';
import { GitHubWriteBackPanel } from '../components/githubWriteBack';
import { TemporaryEnvironmentsPanel } from '../components/qaEnvironments';
import { useT } from '../i18n';

/** Read-only catalog of projects under test and their predefined suites. */
export function QaProjectsPage() {
  const tr = useT();
  const projects = usePolling(() => api.qaProjects(), [], 0);
  const { can } = useSession();
  const { hash } = useLocation();
  // Coming back from a form ("/qa#project-<key>") shows the project that was changed.
  useEffect(() => { if (projects.data && hash) document.getElementById(hash.slice(1))?.scrollIntoView?.(); }, [projects.data, hash]);
  return (
    <>
      {can('APPROVER') && <div className="row page-actions"><Link className="button" to="/qa/projects/new">{tr('Register a project')}</Link></div>}
      <ErrorBanner error={projects.error} />
      {projects.data?.length === 0 && <Empty>{tr('No test projects are registered.')}</Empty>}
      {projects.data?.map(p => (
        <Section key={p.key} id={`project-${p.key}`} title={p.name} actions={<span className={`badge ${p.enabled ? 'badge-ok' : 'badge-muted'}`}>{p.enabled ? 'enabled' : 'disabled'}</span>}>
          <dl className="meta">
            <dt>{tr('Key')}</dt><dd><code>{p.key}</code></dd>
            <dt>{tr('Repository')}</dt><dd><code className="wrap">{p.repositoryUrl}</code></dd>
            <dt>{tr('Default ref')}</dt><dd>{p.defaultRef}</dd>
            <dt>{tr('Repository suites')}</dt><dd>{p.repositorySuitesEnabled ? <>{tr('read from')} <code>{p.suitesFile}</code> at each commit; these registry suites are the fallback</> : tr('not read; registry suites only')}</dd>
            <dt>{tr('Policy')}</dt><dd>{tr('images')} {p.allowedImages.map(i => <code key={i}>{i} </code>)}· {tr('Docker access')} {p.allowDockerAccess ? tr('allowed (approval)') : tr('not allowed')} · {tr('network egress')} {p.allowNetworkEgress ? tr('allowed') : tr('not allowed')} · {tr('max {n} min', { n: Math.round(p.maxTimeoutSeconds / 60) })}</dd>
          </dl>
          <div className="table-wrap"><table>
            <thead><tr><th>{tr('Suite')}</th><th>{tr('Access')}</th><th>{tr('Network')}</th><th>{tr('Max time')}</th><th>{tr('Command')}</th></tr></thead>
            <tbody>{p.suites.map(s => (
              <tr key={s.key}>
                <td><strong>{s.name}</strong><div className="muted small"><code>{s.key}</code>{s.enabled ? '' : tr(' · disabled')}</div></td>
                <td>{s.dockerAccess ? <span className="badge badge-warn">{tr('Docker · approval')}</span> : <span className="badge badge-ok">{tr('isolated')}</span>}</td>
                <td>{s.network}</td>
                <td>{tr('{n} min', { n: Math.round(s.maxTimeoutSeconds / 60) })}</td>
                <td className="wrap-cell"><code>{s.command.join(' ')}</code></td>
              </tr>
            ))}</tbody>
          </table></div>
          <ProjectSchedules project={p} />
          <ProjectEnvironments project={p} />
          <TemporaryEnvironmentsPanel project={p} />
          <GitHubWriteBackPanel project={p} />
          <h3 className="subhead">{tr('AI-generated API tests')}</h3>
          <p className="small"><Link to={`/qa/test-cases?project=${p.key}`}>{tr('See this project\'s test cases')}</Link> · <Link to={`/qa/test-cases/generate?project=${p.key}`}>{tr('Generate with AI')}</Link></p>
          {can('APPROVER') && <ProjectAdminPanel project={p} onChanged={projects.reload} />}
        </Section>
      ))}
      <p className="muted small">{tr('Projects and policies are registered by administrators. Projects can declare further suites in their repository, always within this policy. Use a task\'s "Run test suite" form to run one.')}</p>
    </>
  );
}
