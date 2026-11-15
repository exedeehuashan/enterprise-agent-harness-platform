import { Link } from 'react-router-dom';
import { useSession } from '../session';
import { useT } from '../i18n';

/** What each AI feature does, in words an administrator reads. */
export const FEATURES: Record<string, string> = {
  'qa-failure-analysis': 'Failure analysis',
  'qa-test-generation': 'Test generation',
  planning: 'Task planning',
};

export function useFeatureLabel() {
  const tr = useT();
  return (feature: string) => (FEATURES[feature] ? tr(FEATURES[feature]) : feature);
}

/** The console page an AI call belongs to, from its source. */
export function sourceLink(type: string | null, id: string | null): { to: string; label: string } | null {
  if (!type || !id) return null;
  switch (type) {
    case 'qa-run': return { to: `/runs/${id}`, label: 'Run' };
    case 'task': return { to: `/tasks/${id}`, label: 'Task' };
    case 'qa-draft': {
      const [project, draft] = id.split('/');
      return project && draft ? { to: `/qa/test-cases/${project}/drafts/${draft}`, label: 'Test-generation draft' } : null;
    }
    case 'qa-project': return { to: `/qa/test-cases/drafts?project=${encodeURIComponent(id)}`, label: 'Project' };
    case 'ai-eval': return { to: '/admin#ai-evals', label: 'AI evaluation' };
    default: return null;
  }
}

/** For approvers: a link to the AI calls made for one run, task or draft. */
export function AiCallsLink({ sourceType, sourceId }: { sourceType: string; sourceId: string }) {
  const tr = useT();
  const { can } = useSession();
  if (!can('APPROVER')) return null;
  return <Link to={`/admin/ai-calls?sourceType=${sourceType}&sourceId=${encodeURIComponent(sourceId)}`}>{tr('How AI was called')}</Link>;
}
