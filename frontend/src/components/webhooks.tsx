import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { WebhookCreated, WebhookFormat } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { Empty, ErrorBanner, Section, Time } from './ui';
import { useT } from '../i18n';

const FORMATS: { value: WebhookFormat; label: string; url: string; signing: boolean }[] = [
  { value: 'generic', label: 'Webhook (signed JSON)', url: 'https://hooks.example.com/eahp', signing: false },
  { value: 'slack', label: 'Slack', url: 'https://hooks.slack.com/services/…', signing: false },
  { value: 'dingtalk', label: 'DingTalk 钉钉', url: 'https://oapi.dingtalk.com/robot/send?access_token=…', signing: true },
  { value: 'wecom', label: 'WeCom 企业微信', url: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…', signing: false },
  { value: 'feishu', label: 'Feishu 飞书', url: 'https://open.feishu.cn/open-apis/bot/v2/hook/…', signing: true },
  { value: 'email', label: 'Email', url: '', signing: false },
];

/** Notifications for approvers: webhooks, chat robots and email, their health, a test message, and a generic webhook's one-time secret. */
export function WebhooksPanel() {
  const tr = useT();
  const hooks = usePolling(() => api.webhooks(), [], 15000);
  const action = useAction();
  const [form, setForm] = useState({ name: '', format: 'generic' as WebhookFormat, url: '', secret: '', recipients: '', events: 'RUN_*, APPROVAL_REQUESTED, QA_RESULT_INGESTED' });
  const [created, setCreated] = useState<WebhookCreated | null>(null);
  const [tested, setTested] = useState<Record<string, string>>({});
  const format = FORMATS.find(f => f.value === form.format)!;
  const change = async (call: () => Promise<unknown>) => { if (await action.run(call) !== undefined) void hooks.reload(); };
  const create = async (e: FormEvent) => {
    e.preventDefault();
    const events = form.events.split(',').map(s => s.trim()).filter(Boolean);
    const body: Record<string, unknown> = { name: form.name.trim(), format: form.format, events };
    if (form.format === 'email') body.recipients = form.recipients.split(',').map(s => s.trim()).filter(Boolean);
    else body.url = form.url.trim();
    if (format.signing && form.secret.trim()) body.secret = form.secret.trim();
    const result = await action.run(() => api.createWebhook(body));
    if (result) { setCreated(result.secret ? result : null); setForm({ ...form, name: '', url: '', secret: '', recipients: '' }); void hooks.reload(); }
  };
  const test = async (id: string) => {
    const d = await action.run(() => api.testWebhook(id));
    if (d) { setTested({ ...tested, [id]: d.error ? `Test failed: ${d.error}` : tr('Test message sent') }); void hooks.reload(); }
  };
  return (
    <Section title={tr('Notifications')}>
      <p className="muted small">{tr('EAHP sends each matching event (run lifecycle, approvals, QA results, quarantine suggestions) in order. A webhook receives JSON signed with')}
        <code> X-EAHP-Signature: sha256=HMAC(secret, body)</code>; Slack, DingTalk, WeCom and Feishu robots and email receive a short message with a link.
        Failed deliveries are retried with backoff; after 20 failures in a row a subscription pauses.</p>
      <ErrorBanner error={hooks.error ?? action.error} />
      {created && <div className="notice" role="status">{tr('Secret for')} <strong>{created.subscription.name}</strong> {tr('(shown only now):')} <code>{created.secret}</code>
        <button className="secondary" onClick={() => setCreated(null)}>{tr('Done')}</button></div>}
      {hooks.data?.length === 0 && <Empty>{tr('No notifications.')}</Empty>}
      {hooks.data && hooks.data.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>{tr('Name')}</th><th>{tr('Events')}</th><th>{tr('Health')}</th><th /></tr></thead>
          <tbody>{hooks.data.map(h => (
            <tr key={h.id}>
              <td><strong>{h.name}</strong> <span className="badge badge-muted">{tr(FORMATS.find(f => f.value === (h.format ?? 'generic'))?.label ?? '')}</span>
                <div className="muted small wrap"><code>{h.format === 'email' ? h.url.replace(/^mailto:/, '') : h.url.replace(/([?&](access_token|key)=)[^&]+/, '$1…')}</code></div></td>
              <td className="small">{h.events.join(', ')}</td>
              <td className="small">{!h.enabled ? <span className="badge badge-muted">{tr('paused')}</span> : h.lastError ? <span className="badge badge-bad">{tr('failing')}</span> : <span className="badge badge-ok">{tr('ok')}</span>}
                {h.lastError && <div className="muted">{h.lastError} · {h.consecutiveFailures} in a row</div>}
                {h.lastDeliveryAt && <div className="muted">{tr('last delivered')} <Time value={h.lastDeliveryAt} /></div>}
                {tested[h.id] && <div role="status">{tested[h.id]}</div>}</td>
              <td className="row">
                <button className="secondary" disabled={action.busy} onClick={() => void test(h.id)}>{tr('Send test')}</button>
                <button className="secondary" disabled={action.busy} onClick={() => change(() => api.webhookEnabled(h.id, !h.enabled))}>{h.enabled ? tr('Pause') : tr('Resume')}</button>
                <button className="danger" disabled={action.busy} onClick={() => window.confirm(`Delete ${h.name}?`) && change(() => api.deleteWebhook(h.id))}>{tr('Delete')}</button>
              </td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      <form className="row" onSubmit={create}>
        <label className="inline">{tr('Name')}<input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} required maxLength={120} /></label>
        <label className="inline">{tr('Send to')}<select value={form.format} onChange={e => setForm({ ...form, format: e.target.value as WebhookFormat })}>
          {FORMATS.map(f => <option key={f.value} value={f.value}>{tr(f.label)}</option>)}</select></label>
        {form.format === 'email'
          ? <label className="inline">{tr('Recipients')}<input value={form.recipients} onChange={e => setForm({ ...form, recipients: e.target.value })} required placeholder="qa@example.com, lead@example.com" /></label>
          : <label className="inline">URL<input value={form.url} onChange={e => setForm({ ...form, url: e.target.value })} required placeholder={format.url} /></label>}
        {format.signing && <label className="inline">{tr('Signing secret (optional)')}<input value={form.secret} onChange={e => setForm({ ...form, secret: e.target.value })} placeholder="SEC…" /></label>}
        <label className="inline">{tr('Events')}<input value={form.events} onChange={e => setForm({ ...form, events: e.target.value })} required /></label>
        <button type="submit" className="secondary" disabled={action.busy}>{tr('Add')}</button>
      </form>
    </Section>
  );
}
