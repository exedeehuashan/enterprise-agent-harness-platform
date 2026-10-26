import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { WebhookFormat } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { Empty, ErrorBanner, Section, Time } from './ui';
import { useT } from '../i18n';

export const FORMATS: { value: WebhookFormat; label: string; url: string; signing: boolean }[] = [
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
  const [tested, setTested] = useState<Record<string, string>>({});
  const change = async (call: () => Promise<unknown>) => { if (await action.run(call) !== undefined) void hooks.reload(); };
  const test = async (id: string) => {
    const d = await action.run(() => api.testWebhook(id));
    if (d) { setTested({ ...tested, [id]: d.error ? `Test failed: ${d.error}` : tr('Test message sent') }); void hooks.reload(); }
  };
  return (
    <Section title={tr('Notifications')} actions={<Link className="button secondary" to="/admin/notifications/new">{tr('Add notification')}</Link>}>
      <p className="muted small">{tr('EAHP sends each matching event (run lifecycle, approvals, QA results, quarantine suggestions) in order. A webhook receives JSON signed with')}
        <code> X-EAHP-Signature: sha256=HMAC(secret, body)</code>; Slack, DingTalk, WeCom and Feishu robots and email receive a short message with a link.
        Failed deliveries are retried with backoff; after 20 failures in a row a subscription pauses.</p>
      <ErrorBanner error={hooks.error ?? action.error} />
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
                {h.lastError && <div className="muted">{h.lastError} · {tr('{n} in a row', { n: h.consecutiveFailures })}</div>}
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
    </Section>
  );
}
