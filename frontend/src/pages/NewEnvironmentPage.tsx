import { useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { ErrorBanner, FormPage } from '../components/ui';
import { useAction, usePolling } from '../hooks';
import { useT } from '../i18n';

/** Registers a test environment of a project: where its API and performance tests send requests. */
export function NewEnvironmentPage() {
  const tr = useT();
  const navigate = useNavigate();
  const { project: key = '' } = useParams();
  const projects = usePolling(() => api.qaProjects(), [], 0);
  const project = projects.data?.find(p => p.key === key);
  const action = useAction();
  const [form, setForm] = useState({ key: '', name: '', services: '' });
  const save = async (e: FormEvent) => {
    e.preventDefault();
    // One "service=base URL" per line.
    const services: Record<string, string> = {};
    for (const line of form.services.split('\n')) {
      const i = line.indexOf('=');
      if (i > 0) services[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    }
    if (await action.run(() => api.admin.putEnvironment(key, form.key.trim(), { name: form.name.trim(), services })) !== undefined) navigate(`/qa#project-${key}`);
  };
  return (
    <FormPage back={`/qa#project-${key}`} backLabel={tr('Test projects')} title={project ? tr('New environment for {name}', { name: project.name }) : tr('Add environment')}>
      <ErrorBanner error={projects.error} />
      {projects.data && !project && <p className="error">{tr('No such project: {key}', { key })}</p>}
      {project && (
        <form className="stack" onSubmit={save}>
          <label>{tr('Key')}<input value={form.key} onChange={e => setForm({ ...form, key: e.target.value })} placeholder="uat" required /></label>
          <label>{tr('Name')}<input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} required /></label>
          <label>{tr('Services')}<textarea rows={3} value={form.services} onChange={e => setForm({ ...form, services: e.target.value })}
            placeholder={'order-api=http://host:8081\ncatalog-api=http://host:8082'} required />
            <span className="muted small">{tr('One service per line, as name=base URL.')}</span></label>
          <ErrorBanner error={action.error} />
          <div><button type="submit" disabled={action.busy}>{tr('Save environment')}</button></div>
        </form>
      )}
    </FormPage>
  );
}
