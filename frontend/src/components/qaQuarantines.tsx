import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { FailingTest } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { Empty, ErrorBanner, Section, Time } from './ui';
import { useT } from '../i18n';

/** Short test label: the simple class name for Java (com.example.OrderTest), the file path as-is for JavaScript runners. */
export const testLabel = (cls: string, test: string) => `${cls.includes('/') ? cls : cls.split('.').pop()}${test ? `#${test}` : ''}`;

/**
 * Tests whose failures do not block the release gate for a limited time. Everyone sees them; approvers quarantine
 * a currently failing test with a reason, or release one early.
 */
export function QuarantinePanel({ project, failing }: { project: string; failing: FailingTest[] }) {
  const tr = useT();
  const { can } = useSession();
  const quarantines = usePolling(() => api.quarantines(project), [project], 30000);
  const suggestions = usePolling(() => api.quarantineSuggestions(project), [project], 30000);
  const action = useAction();
  const decide = async (call: () => Promise<unknown>) => { if (await action.run(call) !== undefined) { void suggestions.reload(); void quarantines.reload(); } };
  const candidates = failing.filter(f => !(quarantines.data ?? []).some(q => q.suiteKey === f.suite && q.testClass === f.testClass && q.testName === f.testName));
  const [form, setForm] = useState({ test: '', reason: '', days: 7 });
  const change = async (call: () => Promise<unknown>) => { if (await action.run(call) !== undefined) { setForm({ test: '', reason: '', days: 7 }); void quarantines.reload(); } };
  const create = (e: FormEvent) => {
    e.preventDefault();
    const f = candidates[Number(form.test)];
    if (!f) return;
    void change(() => api.admin.quarantine({ project, suite: f.suite, testClass: f.testClass, testName: f.testName, reason: form.reason.trim(), days: form.days }));
  };
  return (
    <Section title={tr('Quarantined tests')}>
      <p className="muted small">{tr('A quarantined test still runs and is recorded, but a suite that fails only in quarantined tests does not block the gate, as long as it ran at least as many tests as its last passing run. Quarantines expire after at most 30 days.')}</p>
      <ErrorBanner error={quarantines.error ?? suggestions.error ?? action.error} />
      {(suggestions.data?.length ?? 0) > 0 && (
        <div className="stack-tight">
          <h3 className="subhead">{tr('Suggested by EAHP')}</h3>
          {suggestions.data!.map(s => (
            <article key={s.id} className="item small">
              <div className="row spread">
                <div><code title={`${s.testClass}#${s.testName}`}>{testLabel(s.testClass, s.testName)}</code> <span className="muted">in {s.suiteKey}</span>
                  <div>Passed and failed at the same commit on {s.flakyCommits} commit{s.flakyCommits === 1 ? '' : 's'} in {s.evidence.windowDays} days:{' '}
                    {s.evidence.commits.map(c => <code key={c.commit} title={`${c.passed} passed, ${c.failed} failed`}>{c.commit.slice(0, 7)} </code>)}</div>
                  {s.evidence.lastFailure && <div className="muted wrap">Last failure: {s.evidence.lastFailure}</div>}
                </div>
                {can('APPROVER') && <div className="row">
                  <button className="secondary" disabled={action.busy} onClick={() => void decide(() => api.admin.acceptSuggestion(s.id))}>{tr('Quarantine 14 days')}</button>
                  <button className="secondary" disabled={action.busy} onClick={() => {
                    const note = window.prompt(tr('Why is this not flaky? (optional)'));
                    if (note !== null) void decide(() => api.admin.dismissSuggestion(s.id, note.trim()));
                  }}>{tr('Dismiss')}</button>
                </div>}
              </div>
            </article>
          ))}
        </div>
      )}
      {quarantines.data?.length === 0 && <Empty>{tr('No test is quarantined.')}</Empty>}
      {quarantines.data && quarantines.data.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>{tr('Test')}</th><th>{tr('Suite')}</th><th>{tr('Reason')}</th><th>{tr('By')}</th><th>{tr('Expires')}</th>{can('APPROVER') && <th />}</tr></thead>
          <tbody>{quarantines.data.map(q => (
            <tr key={q.id}>
              <td className="wrap-cell"><code title={`${q.testClass}#${q.testName}`}>{testLabel(q.testClass, q.testName)}</code></td>
              <td>{q.suiteKey}</td><td className="wrap-cell">{q.reason}</td><td>{q.createdBy}</td><td><Time value={q.expiresAt} /></td>
              {can('APPROVER') && <td><button className="secondary" disabled={action.busy}
                onClick={() => window.confirm(tr('Release this quarantine? Its failures will block the gate again.')) && change(() => api.admin.releaseQuarantine(q.id))}>{tr('Release')}</button></td>}
            </tr>
          ))}</tbody>
        </table></div>
      )}
      {can('APPROVER') && candidates.length > 0 && (
        <form className="row" onSubmit={create}>
          <label className="inline">{tr('Failing test')}<select value={form.test} onChange={e => setForm({ ...form, test: e.target.value })} required>
            <option value="">{tr('choose…')}</option>
            {candidates.map((f, i) => <option key={i} value={i}>{f.suite}: {testLabel(f.testClass, f.testName)}</option>)}
          </select></label>
          <label className="inline">{tr('Reason')}<input value={form.reason} minLength={10} maxLength={500} onChange={e => setForm({ ...form, reason: e.target.value })} required /></label>
          <label className="inline">{tr('Days')}<input type="number" min={1} max={30} value={form.days} onChange={e => setForm({ ...form, days: Number(e.target.value) })} required /></label>
          <button type="submit" className="secondary" disabled={action.busy}>{tr('Quarantine')}</button>
        </form>
      )}
    </Section>
  );
}
