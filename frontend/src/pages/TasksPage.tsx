import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { TaskStatus } from '../api/types';
import { Empty, ErrorBanner, Pager, Section, Status, Time } from '../components/ui';
import { usePolling } from '../hooks';
import { useSession } from '../session';
import { useT } from '../i18n';

const STATUSES: (TaskStatus | '')[] = ['', 'PENDING', 'RUNNING', 'COMPLETED', 'FAILED'];

export function TasksPage() {
  const tr = useT();
  const { can } = useSession();
  const [status, setStatus] = useState<TaskStatus | ''>('');
  const [page, setPage] = useState(0);
  const tasks = usePolling(() => api.listTasks({ page, status }), [page, status], 5000);

  return (
    <Section title={tr('Tasks')} actions={<>
        <label className="inline">{tr('Status')}
          <select value={status} onChange={e => { setStatus(e.target.value as TaskStatus | ''); setPage(0); }}>
            {STATUSES.map(s => <option key={s} value={s}>{s || tr('All')}</option>)}
          </select>
        </label>
        {can('OPERATOR') && <Link className="button" to="/tasks/new">{tr('New task')}</Link>}
      </>}>
        <ErrorBanner error={tasks.error} />
        {tasks.data && tasks.data.items.length === 0 && <Empty>{status ? tr('No tasks with status {status}.', { status: tr(status.replace(/_/g, ' ')) }) : tr('No tasks.')}</Empty>}
        {tasks.data && tasks.data.items.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{tr('Title')}</th><th>{tr('Status')}</th><th>{tr('Created')}</th><th>{tr('Updated')}</th></tr></thead>
              <tbody>
                {tasks.data.items.map(t => (
                  <tr key={t.id}>
                    <td className="wrap-cell"><Link to={`/tasks/${t.id}`}>{t.title}</Link></td>
                    <td><Status value={t.status} /></td>
                    <td><Time value={t.createdAt} /></td>
                    <td><Time value={t.updatedAt} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {tasks.data && <Pager page={page} totalPages={tasks.data.totalPages} onPage={setPage} />}
    </Section>
  );
}
