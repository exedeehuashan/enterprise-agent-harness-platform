import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { ErrorBanner, Section } from '../components/ui';
import { useAction, usePolling } from '../hooks';
import { useT } from '../i18n';

/** Asks the AI to draft API test cases from an OpenAPI document in a project's repository; opens the draft when done. */
export function GenerateTestsPage() {
  const tr = useT();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const projects = usePolling(() => api.qaProjects(), [], 0);
  const action = useAction();
  const [form, setForm] = useState({ project: params.get('project') ?? '', specPath: '', service: '', ref: '', instructions: '', maxCases: '8' });
  const project = projects.data?.find(p => p.key === form.project);

  useEffect(() => {
    if (!form.project && projects.data?.length) setForm(f => ({ ...f, project: projects.data![0].key }));
  }, [projects.data, form.project]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const body: Record<string, unknown> = { specPath: form.specPath.trim(), service: form.service.trim(), maxCases: Number(form.maxCases) };
    if (form.ref.trim()) body.ref = form.ref.trim();
    if (form.instructions.trim()) body.instructions = form.instructions.trim();
    const draft = await action.run(() => api.generateAiTests(form.project, body));
    if (draft) navigate(`/qa/test-cases/${form.project}/drafts/${draft.id}`);
  };

  return (
    <>
      <p><Link to="/qa/test-cases">← {tr('Test cases')}</Link></p>
      <Section title={tr('Generate test cases with AI')}>
        <p className="muted small">{tr('The model reads the OpenAPI document at the chosen commit and drafts a collection of API test cases, each labelled with a category. The draft is checked by the same rules as repository files before it can run.')}</p>
        <form className="stack" onSubmit={submit}>
          <label>{tr('Project')}
            <select value={form.project} onChange={e => setForm({ ...form, project: e.target.value })} required>
              {projects.data?.map(p => <option key={p.key} value={p.key}>{p.name}</option>)}
            </select>
          </label>
          <label>{tr('OpenAPI file')}<input value={form.specPath} onChange={e => setForm({ ...form, specPath: e.target.value })} placeholder="openapi/order-api.json" required /></label>
          <label>{tr('Service')}<input value={form.service} onChange={e => setForm({ ...form, service: e.target.value })} placeholder="order-api" required /></label>
          <label>{tr('Ref')}<input value={form.ref} onChange={e => setForm({ ...form, ref: e.target.value })} placeholder={project?.defaultRef ?? 'main'} /></label>
          <label>{tr('Focus (optional)')}<textarea value={form.instructions} onChange={e => setForm({ ...form, instructions: e.target.value })} maxLength={2000} rows={3} /></label>
          <label>{tr('Most cases')}<input type="number" min={1} max={30} value={form.maxCases} onChange={e => setForm({ ...form, maxCases: e.target.value })} required /></label>
          <ErrorBanner error={projects.error ?? action.error} />
          <div><button type="submit" disabled={action.busy || !form.project}>{action.busy ? tr('Generating… (usually 1–2 minutes)') : tr('Generate with AI')}</button></div>
        </form>
      </Section>
    </>
  );
}
