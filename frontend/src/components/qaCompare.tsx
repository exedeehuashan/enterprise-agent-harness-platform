import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { SuiteDiff } from '../api/types';
import { useAction } from '../hooks';
import { testLabel } from './qaQuarantines';
import { Empty, ErrorBanner, Section } from './ui';

const split = (t: string) => { const i = t.lastIndexOf('#'); return i < 0 ? testLabel(t, '') : testLabel(t.slice(0, i), t.slice(i + 1)); };

function List({ title, tests, total }: { title: string; tests: string[]; total: number }) {
  if (total === 0) return null;
  return (
    <div className="small"><strong>{title} ({total})</strong>
      <ul>{tests.map(t => <li key={t}><code title={t}>{split(t)}</code></li>)}{total > tests.length && <li className="muted">{total - tests.length} more</li>}</ul>
    </div>
  );
}

/** What changed in the test results between two commits: new failures, fixes, and added or removed tests. */
export function CompareCommits({ project, defaultBase }: { project: string; defaultBase: string }) {
  const [form, setForm] = useState({ base: defaultBase, head: '' });
  const [diffs, setDiffs] = useState<SuiteDiff[] | null>(null);
  const action = useAction();
  const compare = async (e: FormEvent) => {
    e.preventDefault();
    const result = await action.run(() => api.compareCommits(project, form.base.trim(), form.head.trim()));
    if (result) setDiffs(result);
  };
  return (
    <Section title="Compare commits">
      <form className="row" onSubmit={compare}>
        <label className="inline">Base<input value={form.base} onChange={e => setForm({ ...form, base: e.target.value })} maxLength={100} required /></label>
        <label className="inline">Head<input value={form.head} onChange={e => setForm({ ...form, head: e.target.value })} maxLength={100} required /></label>
        <button type="submit" className="secondary" disabled={action.busy}>Compare</button>
      </form>
      <ErrorBanner error={action.error} />
      {diffs?.length === 0 && <Empty>No suite has a result at the head commit.</Empty>}
      {diffs?.map(d => (
        <article key={d.suite} className="item stack-tight">
          <strong>{d.suite}</strong>
          {d.status === 'NO_BASE_RESULT' && <p className="muted small">No result at the base commit to compare with.</p>}
          {d.status === 'COMPARED' && (
            <>
              <p className="small">{d.newFailureCount} new failure{d.newFailureCount === 1 ? '' : 's'}, {d.fixedCount} fixed, {d.stillFailingCount} still failing,
                {' '}{d.addedCount} added{d.sameScope ? `, ${d.removedCount} removed` : ' (different scope: removed tests not compared)'}
                {' '}<span className="muted">({d.base?.tests} → {d.head?.tests} tests)</span></p>
              <List title="New failures" tests={d.newFailures} total={d.newFailureCount} />
              <List title="Fixed" tests={d.fixed} total={d.fixedCount} />
              <List title="Removed" tests={d.removed} total={d.removedCount} />
            </>
          )}
        </article>
      ))}
    </Section>
  );
}
