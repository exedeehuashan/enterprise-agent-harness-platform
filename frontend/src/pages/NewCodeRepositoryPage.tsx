import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { ErrorBanner, FormPage } from '../components/ui';
import { useAction } from '../hooks';
import { useT } from '../i18n';

/** Registers a repository the code agent may work on, and whether its change may be pushed as a draft pull request. */
export function NewCodeRepositoryPage() {
  const tr = useT();
  const navigate = useNavigate();
  const action = useAction();
  const [form, setForm] = useState({ key: '', name: '', repositoryUrl: '', defaultRef: 'main', allowPush: false });
  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (await action.run(() => api.putCodeRepository(form.key.trim(), { name: form.name.trim(), repositoryUrl: form.repositoryUrl.trim(), defaultRef: form.defaultRef.trim(), allowPush: form.allowPush }))) {
      navigate('/admin');
    }
  };
  return (
    <FormPage back="/admin" backLabel={tr('Administration')} title={tr('Add repository')}>
      <form className="stack" onSubmit={save}>
        <label>{tr('Key')}<input value={form.key} onChange={e => setForm({ ...form, key: e.target.value })} required placeholder="online-shop" /></label>
        <label>{tr('Name')}<input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} required /></label>
        <label>{tr('Repository URL')}<input value={form.repositoryUrl} onChange={e => setForm({ ...form, repositoryUrl: e.target.value })} required placeholder="https://github.com/owner/repo.git" /></label>
        <label>{tr('Default ref')}<input value={form.defaultRef} onChange={e => setForm({ ...form, defaultRef: e.target.value })} required /></label>
        <label className="inline"><input type="checkbox" checked={form.allowPush} onChange={e => setForm({ ...form, allowPush: e.target.checked })} />{tr('allow push')}</label>
        <p className="muted small">{tr('With pushing allowed and a GitHub write token configured, the agent\'s change goes to a new branch with a draft pull request; otherwise only the diff is recorded.')}</p>
        <ErrorBanner error={action.error} />
        <div><button type="submit" disabled={action.busy}>{tr('Save repository')}</button></div>
      </form>
    </FormPage>
  );
}
