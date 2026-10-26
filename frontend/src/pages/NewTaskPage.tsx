import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { ErrorBanner, FormPage } from '../components/ui';
import { useAction } from '../hooks';
import { useT } from '../i18n';

/** Creates a task and opens it. */
export function NewTaskPage() {
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
    <FormPage back="/" backLabel={tr('Tasks')} title={tr('New task')}>
      <form className="stack" onSubmit={submit}>
        <label>{tr('Title')}<input value={title} onChange={e => setTitle(e.target.value)} maxLength={255} required /></label>
        <label>{tr('Description')}<textarea value={description} onChange={e => setDescription(e.target.value)} maxLength={10000} rows={4} /></label>
        <ErrorBanner error={action.error} />
        <div><button type="submit" disabled={action.busy || !title.trim()}>{tr('Create task')}</button></div>
      </form>
    </FormPage>
  );
}
