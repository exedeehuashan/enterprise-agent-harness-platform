import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { AutoFix } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { useSession } from '../session';
import { Empty, ErrorBanner, Section, Time } from './ui';

const BADGE: Record<AutoFix['status'], string> = {
  CODING: 'badge-warn', VERIFYING: 'badge-warn', FIXED: 'badge-ok', PARTLY_FIXED: 'badge-warn', NOT_FIXED: 'badge-bad',
  NO_CHANGE: 'badge-muted', CODE_FAILED: 'badge-bad', VERIFY_FAILED: 'badge-bad',
};
const LABEL: Record<AutoFix['status'], string> = {
  CODING: 'code agent working (needs approval)', VERIFYING: 'verifying the pull request', FIXED: 'fixed', PARTLY_FIXED: 'partly fixed',
  NOT_FIXED: 'not fixed', NO_CHANGE: 'no change made', CODE_FAILED: 'code agent failed', VERIFY_FAILED: 'verification did not run',
};

function FixLine({ fix }: { fix: AutoFix }) {
  return (
    <div className="small">
      <span className={`badge ${BADGE[fix.status]}`}>{LABEL[fix.status]}</span>{' '}
      <Link to={`/runs/${fix.codeRunId}`}>code agent run</Link>
      {fix.pullRequestUrl && <> · <a href={fix.pullRequestUrl} target="_blank" rel="noreferrer">draft PR #{fix.prNumber}</a></>}
      {fix.verifyRunId && <> · <Link to={`/runs/${fix.verifyRunId}`}>verification run</Link></>}
      {fix.result && fix.result.fixed !== undefined && <> · {fix.result.fixed} fixed, {fix.result.stillFailing} still failing, {fix.result.newFailures} new failures</>}
      {fix.error && <div className="muted">{fix.error}</div>}
    </div>
  );
}

/** On a failed suite run: hand it to the code agent, or follow the fix already requested (P43). */
export function AutoFixPanel({ runId, project }: { runId: string; project: string }) {
  const { can } = useSession();
  const fixes = usePolling(() => api.autoFixes(project), [project], 10000);
  const action = useAction();
  const fix = fixes.data?.find(f => f.sourceRunId === runId);
  const request = async () => { if (await action.run(() => api.requestAutoFix(runId)) !== undefined) void fixes.reload(); };
  return (
    <section className="triage">
      <strong>Fix with the code agent</strong>
      <ErrorBanner error={action.error} />
      {fix ? <FixLine fix={fix} /> : (
        <p className="small">Claude Code can try to fix these failures. An approver approves the run first; EAHP then opens a draft pull request
          and runs this suite again on it to verify the fix.{' '}
          {can('OPERATOR') && <button className="secondary" disabled={action.busy} onClick={() => void request()}>Fix with code agent</button>}</p>
      )}
    </section>
  );
}

/** Recent auto-fixes of a project and the opt-in for proposing them automatically. */
export function AutoFixesSection({ project }: { project: string }) {
  const { can } = useSession();
  const fixes = usePolling(() => api.autoFixes(project), [project], 15000);
  const settings = usePolling(() => api.autoFixSettings(project), [project], 0);
  const action = useAction();
  const toggle = async (enabled: boolean) => { if (await action.run(() => api.setAutoFix(project, enabled)) !== undefined) void settings.reload(); };
  return (
    <Section title="Auto-fixes" actions={can('APPROVER') && settings.data ? (
      <label className="inline small"><input type="checkbox" checked={settings.data.enabled} disabled={action.busy} onChange={e => void toggle(e.target.checked)} />
        propose a fix for every failed run</label>) : undefined}>
      <ErrorBanner error={fixes.error ?? action.error} />
      {fixes.data?.length === 0 && <Empty>No auto-fix yet. Open a failed suite run and choose "Fix with code agent".</Empty>}
      {fixes.data?.map(f => (
        <article key={f.id} className="item">
          <div className="row spread"><div><strong>{f.suiteKey}</strong> at <code>{f.sourceCommit.slice(0, 7)}</code>{' '}
            <span className="muted small">{f.trigger === 'AUTO' ? 'proposed automatically' : `by ${f.createdBy}`}, <Time value={f.createdAt} /></span></div></div>
          <FixLine fix={f} />
        </article>
      ))}
    </Section>
  );
}
