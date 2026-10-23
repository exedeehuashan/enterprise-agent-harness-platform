import { api } from '../api/client';
import { Empty, ErrorBanner, Section } from '../components/ui';
import { usePolling } from '../hooks';

/** Read-only catalog of projects under test and their predefined suites. */
export function QaProjectsPage() {
  const projects = usePolling(() => api.qaProjects(), [], 0);
  return (
    <>
      <ErrorBanner error={projects.error} />
      {projects.data?.length === 0 && <Empty>No test projects are registered.</Empty>}
      {projects.data?.map(p => (
        <Section key={p.key} title={p.name} actions={<span className={`badge ${p.enabled ? 'badge-ok' : 'badge-muted'}`}>{p.enabled ? 'enabled' : 'disabled'}</span>}>
          <dl className="meta">
            <dt>Key</dt><dd><code>{p.key}</code></dd>
            <dt>Repository</dt><dd><code className="wrap">{p.repositoryUrl}</code></dd>
            <dt>Default ref</dt><dd>{p.defaultRef}</dd>
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
        </Section>
      ))}
      <p className="muted small">Suites are registered by administrators. Use a task's "Run test suite" form to run one.</p>
    </>
  );
}
