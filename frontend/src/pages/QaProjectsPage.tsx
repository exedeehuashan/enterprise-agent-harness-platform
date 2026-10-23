import { api } from '../api/client';
import { Empty, ErrorBanner, Section } from '../components/ui';
import { usePolling } from '../hooks';
import { useSession } from '../session';
import { NewProjectForm, ProjectAdminPanel } from '../components/qaAdmin';
import { ProjectSchedules } from '../components/qaSchedules';
import { StoragePanel } from '../components/storage';

/** Read-only catalog of projects under test and their predefined suites. */
export function QaProjectsPage() {
  const projects = usePolling(() => api.qaProjects(), [], 0);
  const { can } = useSession();
  return (
    <>
      {can('APPROVER') && <NewProjectForm onCreated={projects.reload} />}
      <ErrorBanner error={projects.error} />
      {projects.data?.length === 0 && <Empty>No test projects are registered.</Empty>}
      {projects.data?.map(p => (
        <Section key={p.key} title={p.name} actions={<span className={`badge ${p.enabled ? 'badge-ok' : 'badge-muted'}`}>{p.enabled ? 'enabled' : 'disabled'}</span>}>
          <dl className="meta">
            <dt>Key</dt><dd><code>{p.key}</code></dd>
            <dt>Repository</dt><dd><code className="wrap">{p.repositoryUrl}</code></dd>
            <dt>Default ref</dt><dd>{p.defaultRef}</dd>
            <dt>Repository suites</dt><dd>{p.repositorySuitesEnabled ? <>read from <code>{p.suitesFile}</code> at each commit; these registry suites are the fallback</> : 'not read; registry suites only'}</dd>
            <dt>Policy</dt><dd>images {p.allowedImages.map(i => <code key={i}>{i} </code>)}· Docker access {p.allowDockerAccess ? 'allowed (approval)' : 'not allowed'} · network egress {p.allowNetworkEgress ? 'allowed' : 'not allowed'} · max {Math.round(p.maxTimeoutSeconds / 60)} min</dd>
          </dl>
          <div className="table-wrap"><table>
            <thead><tr><th>Suite</th><th>Access</th><th>Network</th><th>Max time</th><th>Command</th></tr></thead>
            <tbody>{p.suites.map(s => (
              <tr key={s.key}>
                <td><strong>{s.name}</strong><div className="muted small"><code>{s.key}</code>{s.enabled ? '' : ' · disabled'}</div></td>
                <td>{s.dockerAccess ? <span className="badge badge-warn">Docker · approval</span> : <span className="badge badge-ok">isolated</span>}</td>
                <td>{s.network}</td>
                <td>{Math.round(s.maxTimeoutSeconds / 60)} min</td>
                <td className="wrap-cell"><code>{s.command.join(' ')}</code></td>
              </tr>
            ))}</tbody>
          </table></div>
          <ProjectSchedules project={p} />
          {can('APPROVER') && <ProjectAdminPanel project={p} onChanged={projects.reload} />}
        </Section>
      ))}
      {can('APPROVER') && <StoragePanel />}
      <p className="muted small">Projects and policies are registered by administrators. Projects can declare further suites in their repository, always within this policy. Use a task's "Run test suite" form to run one.</p>
    </>
  );
}
