import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, tokenStore } from '../api/client';
import { fakeApi } from './fakeApi';

afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });

describe('api client', () => {
  it('sends the bearer token only in the Authorization header', async () => {
    tokenStore.set('t'.repeat(40));
    const calls = fakeApi({ 'GET /agent-tasks': () => ({ body: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } }) });
    await api.listTasks({ status: 'RUNNING' });
    expect(calls[0].headers.Authorization).toBe(`Bearer ${'t'.repeat(40)}`);
    expect(calls[0].path).toBe('/agent-tasks?page=0&size=20&status=RUNNING&sort=createdAt%2Cdesc');
    expect(calls[0].path).not.toContain('t'.repeat(40));
  });

  it('omits an empty status filter', async () => {
    const calls = fakeApi({ 'GET /agent-tasks': () => ({ body: { items: [] } }) });
    await api.listTasks({ status: '' });
    expect(calls[0].path).not.toContain('status');
  });

  it('turns structured error bodies into ApiError', async () => {
    fakeApi({ 'POST /agent-tasks': () => ({ status: 400, body: { message: 'Request validation failed', fieldErrors: [{ field: 'title', message: 'must not be blank' }] } }) });
    const error = await api.createTask('', null).catch(e => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(400);
    expect(error.fieldErrors).toEqual([{ field: 'title', message: 'must not be blank' }]);
  });

  it('uses the ETag for conditional edits', async () => {
    const task = { id: 'task-1', title: 'A', description: null, status: 'PENDING', createdAt: '', updatedAt: '', version: 3 };
    const calls = fakeApi({
      'GET /agent-tasks/task-1': () => ({ body: task, headers: { ETag: '"3"' } }),
      'PATCH /agent-tasks/task-1': () => ({ body: { ...task, title: 'B', version: 4 } }),
    });
    const { etag } = await api.getTask('task-1');
    await api.editTask('task-1', etag, { title: 'B' });
    expect(calls[1].headers['If-Match']).toBe('"3"');
    expect(calls[1].body).toEqual({ title: 'B' });
  });

  it('sends idempotency keys and exact digests', async () => {
    const calls = fakeApi({
      'POST /model-calls/call-1/executions': () => ({ status: 202, body: { id: 'exec-1', steps: [] } }),
      'POST /approvals/apr-1/decision': () => ({ body: { id: 'apr-1', status: 'REJECTED' } }),
    });
    await api.executePlan('call-1', 'exec-abc');
    await api.decide('apr-1', 'REJECT', 'f'.repeat(64));
    expect(calls[0].headers['Idempotency-Key']).toBe('exec-abc');
    expect(calls[0].body).toEqual({});
    expect(calls[1].body).toEqual({ decision: 'REJECT', expectedDigest: 'f'.repeat(64), reasonCode: 'RISK_NOT_ACCEPTED' });
  });
});
