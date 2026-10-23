import type {
  Approval, Artifact, AuditEvent, Identity, Invocation, ModelCall, Page, PlanExecution, QaProject, QaSummary, Run, Task, TaskStatus, Tool,
} from './types';

const TOKEN_KEY = 'eahp.token';

/** The token lives only in this tab's sessionStorage; it is never put in URLs. */
export const tokenStore = {
  get: (): string | null => { try { return sessionStorage.getItem(TOKEN_KEY); } catch { return null; } },
  set: (token: string) => { try { sessionStorage.setItem(TOKEN_KEY, token); } catch { /* storage unavailable */ } },
  clear: () => { try { sessionStorage.removeItem(TOKEN_KEY); } catch { /* storage unavailable */ } },
};

export class ApiError extends Error {
  constructor(public status: number, message: string, public fieldErrors: { field: string; message: string }[] = []) {
    super(message);
  }
}

/** Fresh idempotency key per user action; retries of the same action should reuse it. */
export const newKey = (prefix: string) => `${prefix}-${crypto.randomUUID()}`;

interface Options { method?: string; body?: unknown; headers?: Record<string, string>; token?: string }

async function request<T>(path: string, { method = 'GET', body, headers = {}, token }: Options = {}): Promise<{ data: T; etag: string | null }> {
  const auth = token ?? tokenStore.get();
  const res = await fetch(`/api/v1${path}`, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(auth ? { Authorization: `Bearer ${auth}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json: unknown = null;
  if (text) { try { json = JSON.parse(text); } catch { json = null; } }
  if (!res.ok) {
    const err = (json ?? {}) as { message?: string; fieldErrors?: { field: string; message: string }[] };
    throw new ApiError(res.status, err.message ?? res.statusText ?? `HTTP ${res.status}`, err.fieldErrors ?? []);
  }
  return { data: json as T, etag: res.headers.get('ETag') };
}

const get = async <T>(path: string) => (await request<T>(path)).data;

/** Downloads with the Authorization header, so artifact links never carry the token in a URL. */
async function artifactContent(id: string): Promise<Response> {
  const auth = tokenStore.get();
  const res = await fetch(`/api/v1/artifacts/${id}/content`, { headers: auth ? { Authorization: `Bearer ${auth}` } : {} });
  if (!res.ok) throw new ApiError(res.status, `Download failed (HTTP ${res.status})`);
  return res;
}
const post = async <T>(path: string, body: unknown = {}, headers: Record<string, string> = {}) =>
  (await request<T>(path, { method: 'POST', body, headers })).data;
const idem = (key: string) => ({ 'Idempotency-Key': key });
const query = (params: Record<string, string | number | undefined>) => {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') q.set(k, String(v));
  const s = q.toString();
  return s ? `?${s}` : '';
};

export const api = {
  me: (token?: string) => request<Identity>('/me', { token }).then(r => r.data),
  tools: () => get<Tool[]>('/tools'),

  listTasks: (p: { page?: number; size?: number; status?: TaskStatus | '' }) =>
    get<Page<Task>>(`/agent-tasks${query({ page: p.page ?? 0, size: p.size ?? 20, status: p.status || undefined, sort: 'createdAt,desc' })}`),
  createTask: (title: string, description: string | null) => post<Task>('/agent-tasks', { title, description }),
  getTask: async (id: string) => {
    const { data, etag } = await request<Task>(`/agent-tasks/${id}`);
    return { task: data, etag: etag ?? `"${data.version}"` };
  },
  editTask: (id: string, etag: string, patch: { title?: string; description?: string | null }) =>
    request<Task>(`/agent-tasks/${id}`, { method: 'PATCH', body: patch, headers: { 'If-Match': etag } }).then(r => r.data),

  startRun: (taskId: string, key: string, tool: string, timeoutSeconds: number, args?: Record<string, unknown>) =>
    post<Run>(`/agent-tasks/${taskId}/runs`, args ? { tool, timeoutSeconds, arguments: args } : { tool, timeoutSeconds }, idem(key)),
  runs: (taskId: string, page = 0) => get<Page<Run>>(`/agent-tasks/${taskId}/runs${query({ page, size: 20 })}`),
  getRun: (id: string) => get<Run>(`/agent-runs/${id}`),
  cancelRun: (id: string) => post<Run>(`/agent-runs/${id}/cancel`, undefined),
  invocations: (runId: string) => get<Invocation[]>(`/agent-runs/${runId}/invocations`),
  artifacts: (runId: string) => get<Artifact[]>(`/agent-runs/${runId}/artifacts`),
  artifactBlob: async (id: string): Promise<Blob> => (await artifactContent(id)).blob(),
  qaSummary: async (artifactId: string): Promise<QaSummary> => (await artifactContent(artifactId)).json() as Promise<QaSummary>,
  qaProjects: () => get<QaProject[]>('/qa/projects'),

  startPlan: (taskId: string, key: string) => post<ModelCall>(`/agent-tasks/${taskId}/plans`, {}, idem(key)),
  plans: (taskId: string) => get<Page<ModelCall>>(`/agent-tasks/${taskId}/plans${query({ page: 0, size: 20 })}`),

  executePlan: (callId: string, key: string) => post<PlanExecution>(`/model-calls/${callId}/executions`, {}, idem(key)),
  executions: (taskId: string) => get<Page<PlanExecution>>(`/agent-tasks/${taskId}/plan-executions${query({ page: 0, size: 20 })}`),
  getExecution: (id: string) => get<PlanExecution>(`/plan-executions/${id}`),
  cancelExecution: (id: string) => post<PlanExecution>(`/plan-executions/${id}/cancel`, undefined),

  approvals: (page = 0) => get<Page<Approval>>(`/approvals${query({ page, size: 50 })}`),
  approval: (id: string) => get<Approval>(`/approvals/${id}`),
  decide: (id: string, decision: 'APPROVE' | 'REJECT', expectedDigest: string) =>
    post<Approval>(`/approvals/${id}/decision`, {
      decision, expectedDigest, reasonCode: decision === 'APPROVE' ? 'REVIEWED' : 'RISK_NOT_ACCEPTED',
    }),
  audit: (runId: string) => get<Page<AuditEvent>>(`/audit-events${query({ runId, page: 0, size: 100 })}`),
};
