import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { tokenStore } from '../api/client';
import { SessionProvider } from '../session';
import { fakeApi } from './fakeApi';

const TOKEN = 'a'.repeat(40);
const admin = { name: 'admin', roles: ['APPROVER', 'OPERATOR', 'VIEWER'] };
const call = (id: number, extra: object = {}) => ({ id, createdAt: '2026-11-15T01:00:00Z', feature: 'qa-failure-analysis', provider: 'anthropic', model: 'claude-opus-5-5',
  inputTokens: 1200, outputTokens: 300, maxOutputTokens: 2000, durationMs: 4200, actor: 'operator', status: 'ok', error: null, sourceType: 'qa-run', sourceId: 'run-7', ...extra });

function renderApp(path: string) {
  return render(<MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><SessionProvider><App /></SessionProvider></MemoryRouter>);
}

afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });

describe('AI call log', () => {
  it('lists calls with what they were for, filters in the URL and pages', async () => {
    tokenStore.set(TOKEN);
    const calls = fakeApi({
      'GET /me': () => ({ body: admin }),
      'GET /admin/ai/calls/features': () => ({ body: ['planning', 'qa-failure-analysis'] }),
      'GET /admin/ai/calls': () => ({ body: { items: [call(2), call(1, { status: 'failed', error: 'AI_BUDGET_EXHAUSTED', feature: 'planning', sourceType: 'task', sourceId: 'task-1' })],
        page: 0, size: 20, totalElements: 30, totalPages: 2 } }),
    });
    renderApp('/admin/ai-calls');
    await screen.findByText('AI_BUDGET_EXHAUSTED');
    const row = screen.getAllByRole('row')[1];
    expect(within(row).getByText('Failure analysis')).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: 'Run' })).toHaveAttribute('href', '/runs/run-7');
    expect(within(row).getByText('1,200 in · 300 out')).toBeInTheDocument();
    expect(within(row).getByText('4.2 s')).toBeInTheDocument();
    expect(within(row).getAllByRole('link')[0]).toHaveAttribute('href', '/admin/ai-calls/2');
    const failed = screen.getByText('AI_BUDGET_EXHAUSTED').closest('tr')!;
    expect(within(failed).getByRole('link', { name: 'Task' })).toHaveAttribute('href', '/tasks/task-1');
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(calls.some(c => c.path.startsWith('/admin/ai/calls?') && c.path.includes('page=1'))).toBe(true));
    await userEvent.selectOptions(screen.getByLabelText('Result'), 'failed');
    await waitFor(() => expect(calls.filter(c => c.path.startsWith('/admin/ai/calls?')).at(-1)!.path).toBe('/admin/ai/calls?status=failed&page=0&size=20'));
  });

  it('shows the redacted prompt and the answer of one call', async () => {
    tokenStore.set(TOKEN);
    fakeApi({
      'GET /me': () => ({ body: admin }),
      'GET /admin/ai/calls/2': () => ({ body: { call: call(2), systemPrompt: 'You are a senior test engineer.', userPrompt: 'Authorization: Bearer [REDACTED]',
        answer: '{"category":"ENVIRONMENT"}', contentStored: true, contentExpiredAt: null } }),
    });
    renderApp('/admin/ai-calls/2');
    expect(await screen.findByText('You are a senior test engineer.')).toBeInTheDocument();
    expect(screen.getByText('Authorization: Bearer [REDACTED]')).toBeInTheDocument();
    expect(screen.getByText(/"category": "ENVIRONMENT"/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Run' })).toHaveAttribute('href', '/runs/run-7');
  });

  it('is only for approvers', async () => {
    tokenStore.set(TOKEN);
    fakeApi({ 'GET /me': () => ({ body: { name: 'operator', roles: ['OPERATOR', 'VIEWER'] } }), 'GET /agent-tasks': () => ({ body: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } }) });
    renderApp('/admin/ai-calls');
    await waitFor(() => expect(screen.queryByText('AI calls')).not.toBeInTheDocument());
  });
});
