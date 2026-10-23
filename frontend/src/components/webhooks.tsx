import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { WebhookCreated } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { Empty, ErrorBanner, Section, Time } from './ui';

/** Outgoing webhooks for approvers: where EAHP events are sent, their health, and the one-time signing secret. */
export function WebhooksPanel() {
  const hooks = usePolling(() => api.webhooks(), [], 15000);
  const action = useAction();
  const [form, setForm] = useState({ name: '', url: '', events: 'RUN_*, APPROVAL_REQUESTED, QA_RESULT_INGESTED' });
  const [created, setCreated] = useState<WebhookCreated | null>(null);
  const change = async (call: () => Promise<unknown>) => { if (await action.run(call) !== undefined) void hooks.reload(); };
  const create = async (e: FormEvent) => {
    e.preventDefault();
    const events = form.events.split(',').map(s => s.trim()).filter(Boolean);
    const result = await action.run(() => api.createWebhook({ name: form.name.trim(), url: form.url.trim(), events }));
    if (result) { setCreated(result); setForm({ ...form, name: '', url: '' }); void hooks.reload(); }
  };
  return (
    <Section title="Webhooks">
      <p className="muted small">EAHP posts each matching event (run lifecycle, approvals, QA results) as JSON, in order, signed with
        <code> X-EAHP-Signature: sha256=HMAC(secret, body)</code>. Failed deliveries are retried with backoff; after 20 failures in a row the webhook pauses.</p>
      <ErrorBanner error={hooks.error ?? action.error} />
      {created && <div className="notice" role="status">Secret for <strong>{created.subscription.name}</strong> (shown only now): <code>{created.secret}</code>
        <button className="secondary" onClick={() => setCreated(null)}>Done</button></div>}
      {hooks.data?.length === 0 && <Empty>No webhooks.</Empty>}
      {hooks.data && hooks.data.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>Name</th><th>Events</th><th>Health</th><th /></tr></thead>
          <tbody>{hooks.data.map(h => (
            <tr key={h.id}>
              <td><strong>{h.name}</strong><div className="muted small wrap"><code>{h.url}</code></div></td>
              <td className="small">{h.events.join(', ')}</td>
              <td className="small">{!h.enabled ? <span className="badge badge-muted">paused</span> : h.lastError ? <span className="badge badge-bad">failing</span> : <span className="badge badge-ok">ok</span>}
                {h.lastError && <div className="muted">{h.lastError} · {h.consecutiveFailures} in a row</div>}
                {h.lastDeliveryAt && <div className="muted">last delivered <Time value={h.lastDeliveryAt} /></div>}</td>
              <td className="row">
                <button className="secondary" disabled={action.busy} onClick={() => change(() => api.webhookEnabled(h.id, !h.enabled))}>{h.enabled ? 'Pause' : 'Resume'}</button>
                <button className="danger" disabled={action.busy} onClick={() => window.confirm(`Delete webhook ${h.name}?`) && change(() => api.deleteWebhook(h.id))}>Delete</button>
              </td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      <form className="row" onSubmit={create}>
        <label className="inline">Name<input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} required maxLength={120} /></label>
        <label className="inline">URL<input value={form.url} onChange={e => setForm({ ...form, url: e.target.value })} required placeholder="https://hooks.example.com/eahp" /></label>
        <label className="inline">Events<input value={form.events} onChange={e => setForm({ ...form, events: e.target.value })} required /></label>
        <button type="submit" className="secondary" disabled={action.busy}>Add webhook</button>
      </form>
    </Section>
  );
}
