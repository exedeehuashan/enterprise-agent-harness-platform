import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { WebhookCreated, WebhookFormat } from '../api/types';
import { FORMATS } from '../components/webhooks';
import { ErrorBanner, FormPage } from '../components/ui';
import { useAction } from '../hooks';
import { useT } from '../i18n';

/** Adds a notification (webhook, chat robot or email); a generic webhook's signing secret is shown here once. */
export function NewNotificationPage() {
  const tr = useT();
  const navigate = useNavigate();
  const action = useAction();
  const [form, setForm] = useState({ name: '', format: 'generic' as WebhookFormat, url: '', secret: '', recipients: '', events: 'RUN_*, APPROVAL_REQUESTED, QA_RESULT_INGESTED' });
  const [created, setCreated] = useState<WebhookCreated | null>(null);
  const format = FORMATS.find(f => f.value === form.format)!;
  const create = async (e: FormEvent) => {
    e.preventDefault();
    const events = form.events.split(',').map(s => s.trim()).filter(Boolean);
    const body: Record<string, unknown> = { name: form.name.trim(), format: form.format, events };
    if (form.format === 'email') body.recipients = form.recipients.split(',').map(s => s.trim()).filter(Boolean);
    else body.url = form.url.trim();
    if (format.signing && form.secret.trim()) body.secret = form.secret.trim();
    const result = await action.run(() => api.createWebhook(body));
    if (!result) return;
    if (result.secret) setCreated(result);
    else navigate('/admin');
  };
  return (
    <FormPage back="/admin" backLabel={tr('Administration')} title={tr('Add notification')}>
      {created ? (
        <div className="stack">
          <div className="notice" role="status">{tr('Secret for')} <strong>{created.subscription.name}</strong> {tr('(shown only now):')} <code>{created.secret}</code></div>
          <p className="muted small">{tr('Copy it now and hand it over securely; it cannot be shown again.')}</p>
          <div><button onClick={() => navigate('/admin')}>{tr('Done')}</button></div>
        </div>
      ) : (
        <form className="stack" onSubmit={create}>
          <label>{tr('Name')}<input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} required maxLength={120} /></label>
          <label>{tr('Send to')}<select value={form.format} onChange={e => setForm({ ...form, format: e.target.value as WebhookFormat })}>
            {FORMATS.map(f => <option key={f.value} value={f.value}>{tr(f.label)}</option>)}</select></label>
          {form.format === 'email'
            ? <label>{tr('Recipients')}<input value={form.recipients} onChange={e => setForm({ ...form, recipients: e.target.value })} required placeholder="qa@example.com, lead@example.com" /></label>
            : <label>URL<input value={form.url} onChange={e => setForm({ ...form, url: e.target.value })} required placeholder={format.url} /></label>}
          {format.signing && <label>{tr('Signing secret (optional)')}<input value={form.secret} onChange={e => setForm({ ...form, secret: e.target.value })} placeholder="SEC…" /></label>}
          <label>{tr('Events')}<input value={form.events} onChange={e => setForm({ ...form, events: e.target.value })} required /></label>
          <ErrorBanner error={action.error} />
          <div><button type="submit" disabled={action.busy}>{tr('Add')}</button></div>
        </form>
      )}
    </FormPage>
  );
}
