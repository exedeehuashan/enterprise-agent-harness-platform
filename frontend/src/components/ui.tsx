import type { ReactNode } from 'react';
import { ApiError } from '../api/client';
import { useT } from '../i18n';

const TONE: Record<string, string> = {
  COMPLETED: 'ok', VALIDATED: 'ok', APPROVED: 'ok', CONSUMED: 'ok', PASS: 'ok', PASSED: 'ok', FAIL: 'bad',
  RUNNING: 'active', QUEUED: 'active', LAUNCHED: 'active', STARTED: 'active',
  WAITING_APPROVAL: 'warn', PENDING: 'warn', NEEDS_REVIEW: 'warn',
  FAILED: 'bad', TESTS_FAILED: 'bad', BUILD_FAILED: 'bad', SUITE_TIMEOUT: 'bad', NO_TESTS_FOUND: 'bad', RUNNER_ERROR: 'bad',
  REPORTS_UNREADABLE: 'bad', TIMED_OUT: 'bad', REJECTED: 'bad', EXPIRED: 'bad', INVALIDATED: 'bad', LOST: 'bad', UNKNOWN: 'bad',
  CANCELLED: 'muted', SKIPPED: 'muted', SUPERSEDED: 'muted',
};

export function Status({ value }: { value: string | null | undefined }) {
  const tr = useT();
  if (!value) return <span className="muted">—</span>;
  return <span className={`badge badge-${TONE[value] ?? 'muted'}`}>{tr(value.replace(/_/g, ' '))}</span>;
}

export function ErrorBanner({ error }: { error: Error | null | undefined }) {
  const tr = useT();
  if (!error) return null;
  const status = error instanceof ApiError ? error.status : null;
  const hint = status === 401 ? tr('Your session is no longer valid. Sign in again.')
    : status === 403 ? tr('Your role does not allow this action.')
    : status === 412 ? tr('The task changed since you loaded it. Reload and reapply your edit.')
    : null;
  return (
    <div className="error" role="alert">
      <strong>{status ? `${status}: ` : ''}{error.message}</strong>
      {hint && <div>{hint}</div>}
      {error instanceof ApiError && error.fieldErrors.length > 0 && (
        <ul>{error.fieldErrors.map(f => <li key={f.field}>{f.field}: {f.message}</li>)}</ul>
      )}
    </div>
  );
}

export function Time({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">—</span>;
  const date = new Date(value);
  return <time dateTime={value} title={value}>{date.toLocaleString()}</time>;
}

export function ShortId({ id }: { id: string | null | undefined }) {
  if (!id) return <span className="muted">—</span>;
  return <code title={id}>{id.slice(0, 8)}</code>;
}

export function Section({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      <header className="card-head"><h2>{title}</h2>{actions && <div className="actions">{actions}</div>}</header>
      {children}
    </section>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}
