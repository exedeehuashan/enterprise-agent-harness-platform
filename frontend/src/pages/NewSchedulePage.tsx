import { useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { QaProject } from '../api/types';
import { ErrorBanner, FormPage } from '../components/ui';
import { useAction, usePolling } from '../hooks';
import { useT } from '../i18n';

/** Adds a scheduled run to a project: when the ref changes, or on a cron schedule (UTC). */
export function NewSchedulePage() {
  const tr = useT();
  const { project: key = '' } = useParams();
  const projects = usePolling(() => api.qaProjects(), [], 0);
  const project = projects.data?.find(p => p.key === key);
  return (
    <FormPage back={`/qa#project-${key}`} backLabel={tr('Test projects')} title={project ? tr('New schedule for {name}', { name: project.name }) : tr('Add schedule')}>
      <ErrorBanner error={projects.error} />
      {project && <ScheduleForm project={project} />}
      {projects.data && !project && <p className="error">{tr('No such project: {key}', { key })}</p>}
    </FormPage>
  );
}

function ScheduleForm({ project }: { project: QaProject }) {
  const tr = useT();
  const navigate = useNavigate();
  const action = useAction();
  const [form, setForm] = useState({ suite: project.suites[0]?.key ?? '', ref: project.defaultRef, kind: 'ON_CHANGE', cron: '0 0 2 * * *', environment: '' });
  const create = async (e: FormEvent) => {
    e.preventDefault();
    const created = await action.run(() => api.admin.createSchedule({ project: project.key, suite: form.suite.trim(), ref: form.ref.trim(), kind: form.kind,
      ...(form.kind === 'CRON' ? { cron: form.cron.trim() } : {}), ...(form.environment.trim() ? { environment: form.environment.trim() } : {}) }));
    if (created) navigate(`/qa#project-${project.key}`);
  };
  return (
    <form className="stack" onSubmit={create}>
      <label>{tr('Suite or API collection')}<input value={form.suite} onChange={e => setForm({ ...form, suite: e.target.value })} list="schedule-suites" required /></label>
      <datalist id="schedule-suites">{project.suites.map(s => <option key={s.key} value={s.key}>{s.name}</option>)}</datalist>
      <label>{tr('Environment')}<input value={form.environment} onChange={e => setForm({ ...form, environment: e.target.value })} placeholder={tr('for API tests only')} /></label>
      <label>{tr('Ref')}<input value={form.ref} onChange={e => setForm({ ...form, ref: e.target.value })} required /></label>
      <label>{tr('When')}<select value={form.kind} onChange={e => setForm({ ...form, kind: e.target.value })}>
        <option value="ON_CHANGE">{tr('when the ref changes')}</option><option value="CRON">{tr('on a schedule (UTC)')}</option></select></label>
      {form.kind === 'CRON' && <label>{tr('Cron')}<input value={form.cron} onChange={e => setForm({ ...form, cron: e.target.value })} title={tr('second minute hour day month weekday')} />
        <span className="muted small">{tr('second minute hour day month weekday')}</span></label>}
      <ErrorBanner error={action.error} />
      <div><button type="submit" disabled={action.busy}>{tr('Add schedule')}</button></div>
    </form>
  );
}
