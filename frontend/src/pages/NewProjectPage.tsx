import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { lines } from '../components/qaAdmin';
import { ErrorBanner, FormPage } from '../components/ui';
import { useAction } from '../hooks';
import { useT } from '../i18n';

/** Registers a new project under test. The policy defaults are conservative: no Docker access, 30 minutes. */
export function NewProjectPage() {
  const tr = useT();
  const navigate = useNavigate();
  const [form, setForm] = useState({ key: '', name: '', repositoryUrl: '', defaultRef: 'main', images: 'maven:3.9.11-eclipse-temurin-17' });
  const action = useAction();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const created = await action.run(() => api.admin.create({
      key: form.key.trim(), name: form.name.trim(), repositoryUrl: form.repositoryUrl.trim(), defaultRef: form.defaultRef.trim(), allowedImages: lines(form.images),
    }));
    if (created) navigate('/qa');
  };
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  return (
    <FormPage back="/qa" backLabel={tr('Test projects')} title={tr('Register a project')}>
      <form className="stack" onSubmit={submit}>
        <label>{tr('Key')}<input value={form.key} onChange={set('key')} placeholder="billing" maxLength={60} required /></label>
        <label>{tr('Name')}<input value={form.name} onChange={set('name')} maxLength={120} required /></label>
        <label>{tr('Default ref')}<input value={form.defaultRef} onChange={set('defaultRef')} maxLength={100} required /></label>
        <label>{tr('Repository URL (https)')}<input value={form.repositoryUrl} onChange={set('repositoryUrl')} placeholder="https://github.com/owner/repo.git" required /></label>
        <label>{tr('Allowed runner images (one per line)')}<textarea rows={2} value={form.images} onChange={set('images')} /></label>
        <p className="muted small">{tr('New projects start without Docker access and with a 30-minute limit. Add suites, triggers and a gate after registering.')}</p>
        <ErrorBanner error={action.error} />
        <div><button type="submit" disabled={action.busy}>{tr('Register project')}</button></div>
      </form>
    </FormPage>
  );
}
