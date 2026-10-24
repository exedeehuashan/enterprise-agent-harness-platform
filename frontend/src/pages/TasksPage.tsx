import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { TaskStatus } from '../api/types';
import { Empty, ErrorBanner, Section, Status, Time } from '../components/ui';
import { useAction, usePolling } from '../hooks';
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
    <>
      {can('OPERATOR') && <CreateTask />}
      <Section title={tr('Tasks')} actions={
        <label className="inline">{tr('Status')}
          <select value={status} onChange={e => { setStatus(e.target.value as TaskStatus | ''); setPage(0); }}>
            {STATUSES.map(s => <option key={s} value={s}>{s || tr('All')}</option>)}
          </select>
        </label>
      }>
        <ErrorBanner error={tasks.error} />
        {tasks.data && tasks.data.items.length === 0 && <Empty>{status ? tr('No tasks with status {status}.', { status: tr(status.replace(/_/g, ' ')) }) : tr('No tasks.')}</Empty>}
        {tasks.data && tasks.data.items.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{tr('Title')}</th><th>{tr('Status')}</th><th>{tr('Created')}</th><th>{tr('Updated')}</th></tr></thead>
              <tbody>
                {tasks.data.items.map(t => (
                  <tr key={t.id}>
                    <td><Link to={`/tasks/${t.id}`}>{t.title}</Link></td>
                    <td><Status value={t.status} /></td>
                    <td><Time value={t.createdAt} /></td>
                    <td><Time value={t.updatedAt} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {tasks.data && tasks.data.totalPages > 1 && (
          <nav className="pager">
            <button className="secondary" disabled={page === 0} onClick={() => setPage(p => p - 1)}>{tr('Previous')}</button>
            <span>{tr('Page {page} of {pages}', { page: page + 1, pages: tasks.data.totalPages })}</span>
            <button className="secondary" disabled={page + 1 >= tasks.data.totalPages} onClick={() => setPage(p => p + 1)}>{tr('Next')}</button>
          </nav>
        )}
      </Section>
    </>
  );
}

function CreateTask() {
  const tr = useT();
  const navigate = useNavigate();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const action = useAction();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const task = await action.run(() => api.createTask(title.trim(), description.trim() || null));
    if (task) navigate(`/tasks/${task.id}`);
  };

  return (
    <Section title={tr('New task')}>
      <form className="stack" onSubmit={submit}>
        <label>{tr('Title')}<input value={title} onChange={e => setTitle(e.target.value)} maxLength={255} required /></label>
        <label>{tr('Description')}<textarea value={description} onChange={e => setDescription(e.target.value)} maxLength={10000} rows={3} /></label>
        <ErrorBanner error={action.error} />
        <div><button type="submit" disabled={action.busy || !title.trim()}>{tr('Create task')}</button></div>
      </form>
    </Section>
  );
}
