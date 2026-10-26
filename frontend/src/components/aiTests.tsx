import type { AiTestDraft, ApiTestCase } from '../api/types';
import { useT } from '../i18n';

/** Downloads a draft as an .eahp/api-tests.json file to review and commit. */
export function download(draft: AiTestDraft) {
  const file = JSON.stringify({ schemaVersion: 1, collections: [draft.collection] }, null, 2) + '\n';
  const url = URL.createObjectURL(new Blob([file], { type: 'application/json' }));
  const a = document.createElement('a');
  a.href = url; a.download = `${draft.collection?.key ?? 'ai-tests'}.api-tests.json`; a.click();
  URL.revokeObjectURL(url);
}

/** What a case checks, in the order the generator is asked to cover them. */
export const CASE_CATEGORIES: Record<string, string> = {
  'happy-path': 'happy path', validation: 'validation', 'not-found': 'not found', idempotency: 'idempotency', concurrency: 'concurrency', 'state-rule': 'state rule',
};

/** The translated label of a case category; unlabelled cases are "uncategorised". */
export function useCategoryLabel() {
  const tr = useT();
  return (c?: string | null) => tr(c && CASE_CATEGORIES[c] ? CASE_CATEGORIES[c] : 'uncategorised');
}

/** How many cases of each category a draft has. */
export function CategoryCounts({ cases }: { cases: ApiTestCase[] }) {
  const label = useCategoryLabel();
  const counts = new Map<string, number>();
  for (const c of cases) counts.set(label(c.category), (counts.get(label(c.category)) ?? 0) + 1);
  return <div className="row chips">{[...counts].map(([k, n]) => <span key={k} className="badge badge-muted">{k} · {n}</span>)}</div>;
}

/** Every case with its category and steps (request and expected status). */
export function CasesTable({ cases }: { cases: ApiTestCase[] }) {
  const tr = useT();
  const label = useCategoryLabel();
  const status = (s?: number | number[]) => (Array.isArray(s) ? s.join('/') : s ?? '');
  return (
    <div className="table-wrap"><table className="cases">
      <colgroup><col className="c-id" /><col className="c-name" /><col className="c-category" /><col /></colgroup>
      <thead><tr><th>{tr('ID')}</th><th>{tr('Name')}</th><th>{tr('Category')}</th><th>{tr('Steps')}</th></tr></thead>
      <tbody>
        {cases.map(c => (
          <tr key={c.id} id={`case-${c.id}`}>
            <td><code>{c.id}</code></td>
            <td>{c.name}</td>
            <td><span className="badge badge-muted">{label(c.category)}</span></td>
            <td>{(c.steps ?? []).map((s, i) => (
              <div key={i} className="step"><code>{s.request?.method} {s.request?.path}</code> <span className="muted">→ {status(s.expect?.status)}</span></div>
            ))}</td>
          </tr>
        ))}
      </tbody>
    </table></div>
  );
}
