import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { QaProject } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { ErrorBanner, Status, Time } from './ui';

/** Scheduled runs of a project: everyone can see them; approvers can add, pause and remove them. */
export function ProjectSchedules({ project }: { project: QaProject }) {
  const { can } = useSession();
  const schedules = usePolling(() => api.schedules(project.key), [project.key], 15000);
  const action = useAction();
  const [form, setForm] = useState({ suite: project.suites[0]?.key ?? '', ref: project.defaultRef, kind: 'ON_CHANGE', cron: '0 0 2 * * *' });
  const change = async (call: () => Promise<unknown>) => { if (await action.run(call) !== undefined) void schedules.reload(); };
  const create = (e: FormEvent) => {
    e.preventDefault();
    void change(() => api.admin.createSchedule({ project: project.key, suite: form.suite.trim(), ref: form.ref.trim(), kind: form.kind,
      ...(form.kind === 'CRON' ? { cron: form.cron.trim() } : {}) }));
  };
  return (
    <>
      <h3 className="subhead">Schedules</h3>
      <ErrorBanner error={schedules.error ?? action.error} />
      {schedules.data?.length === 0 && <p className="muted small">No scheduled runs.</p>}
      {schedules.data && schedules.data.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>Suite</th><th>When</th><th>Last commit</th><th>Last run</th><th>Next</th>{can('APPROVER') && <th />}</tr></thead>
          <tbody>{schedules.data.map(s => (
            <tr key={s.id}>
              <td>{s.suiteKey} <span className="muted small">@ {s.ref}</span>{!s.enabled && <span className="badge badge-muted"> paused</span>}</td>
              <td>{s.kind === 'ON_CHANGE' ? 'when the ref changes' : <code>{s.cron}</code>}</td>
              <td>{s.lastCommit ? <code>{s.lastCommit.slice(0, 7)}</code> : '—'}{s.lastError && <div className="muted small">{s.lastError}</div>}</td>
              <td>{s.lastRunId ? <Link to={`/runs/${s.lastRunId}`}><Status value={s.lastRunStatus} /></Link> : '—'}</td>
              <td>{s.kind === 'CRON' ? <Time value={s.nextRunAt} /> : <span className="muted small">checked <Time value={s.lastCheckedAt} /></span>}</td>
              {can('APPROVER') && <td className="row">
                <button className="secondary" disabled={action.busy} onClick={() => change(() => api.admin.scheduleEnabled(s.id, !s.enabled))}>{s.enabled ? 'Pause' : 'Resume'}</button>
                <button className="danger" disabled={action.busy} onClick={() => window.confirm('Delete this schedule?') && change(() => api.admin.deleteSchedule(s.id))}>Delete</button>
              </td>}
            </tr>
          ))}</tbody>
        </table></div>
      )}
      {can('APPROVER') && (
        <form className="row" onSubmit={create}>
          <label className="inline">Suite<input value={form.suite} onChange={e => setForm({ ...form, suite: e.target.value })} required /></label>
          <label className="inline">Ref<input value={form.ref} onChange={e => setForm({ ...form, ref: e.target.value })} required /></label>
          <label className="inline">When<select value={form.kind} onChange={e => setForm({ ...form, kind: e.target.value })}>
            <option value="ON_CHANGE">when the ref changes</option><option value="CRON">on a schedule (UTC)</option></select></label>
          {form.kind === 'CRON' && <label className="inline">Cron<input value={form.cron} onChange={e => setForm({ ...form, cron: e.target.value })} title="second minute hour day month weekday" /></label>}
          <button type="submit" className="secondary" disabled={action.busy}>Add schedule</button>
        </form>
      )}
    </>
  );
}
