import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { tokenStore } from '../api/client';
import { SessionProvider } from '../session';
import { LanguageProvider, useT } from '../i18n';
import { fakeApi } from './fakeApi';

const TOKEN = 'a'.repeat(40);
const emptyPage = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

function renderApp(path = '/') {
  return render(<MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><SessionProvider><App /></SessionProvider></MemoryRouter>);
}

afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });

describe('console', () => {
  it('signs in with a token and stores it only after the server accepts it', async () => {
    fakeApi({
      'GET /me': c => c.headers.Authorization === `Bearer ${TOKEN}`
        ? { body: { name: 'operator', roles: ['OPERATOR', 'VIEWER'] } } : { status: 401, body: { message: 'Unauthorized' } },
      'GET /agent-tasks': () => ({ body: emptyPage }),
    });
    renderApp();
    await userEvent.type(screen.getByLabelText('API token'), 'b'.repeat(40));
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('401');
    expect(tokenStore.get()).toBeNull();

    await userEvent.clear(screen.getByLabelText('API token'));
    await userEvent.type(screen.getByLabelText('API token'), TOKEN);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('operator')).toBeInTheDocument();
    expect(tokenStore.get()).toBe(TOKEN);
    expect(screen.queryByRole('link', { name: 'Approvals' })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'New task' })).toBeInTheDocument();
  });

  it('hides write actions from viewers and shows approvals to approvers', async () => {
    tokenStore.set(TOKEN);
    fakeApi({ 'GET /me': () => ({ body: { name: 'viewer', roles: ['VIEWER'] } }), 'GET /agent-tasks': () => ({ body: emptyPage }) });
    const { unmount } = renderApp();
    expect(await screen.findByText('No tasks.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'New task' })).not.toBeInTheDocument();
    unmount();

    fakeApi({ 'GET /me': () => ({ body: { name: 'admin', roles: ['APPROVER', 'OPERATOR', 'VIEWER'] } }), 'GET /agent-tasks': () => ({ body: emptyPage }) });
    renderApp();
    expect(await screen.findByRole('link', { name: 'Approvals' })).toBeInTheDocument();
  });

  it('approves a pending request with its exact digest', async () => {
    tokenStore.set(TOKEN);
    const digest = 'c'.repeat(64);
    const pending = { id: 'apr-1', runId: 'run-12345678', inputDigest: digest, status: 'PENDING', requestedAt: '2026-10-23T00:00:00Z', expiresAt: '2026-10-23T00:30:00Z', decidedAt: null, decidedBy: null, reasonCode: null };
    let decided = false;
    const calls = fakeApi({
      'GET /me': () => ({ body: { name: 'admin', roles: ['APPROVER', 'OPERATOR', 'VIEWER'] } }),
      'GET /approvals': () => ({ body: { ...emptyPage, items: [decided ? { ...pending, status: 'APPROVED', decidedBy: 'admin' } : pending] } }),
      'POST /approvals/apr-1/decision': () => { decided = true; return { body: { ...pending, status: 'APPROVED' } }; },
    });
    renderApp('/approvals');
    await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(screen.getByText('Nothing is waiting for approval.')).toBeInTheDocument());
    const decision = calls.find(c => c.method === 'POST');
    expect(decision?.body).toEqual({ decision: 'APPROVE', expectedDigest: digest, reasonCode: 'REVIEWED' });
  });

  it('redirects non-approvers away from the approvals page', async () => {
    tokenStore.set(TOKEN);
    fakeApi({ 'GET /me': () => ({ body: { name: 'operator', roles: ['OPERATOR', 'VIEWER'] } }), 'GET /agent-tasks': () => ({ body: emptyPage }) });
    renderApp('/approvals');
    expect(await screen.findByRole('heading', { name: 'Tasks' })).toBeInTheDocument();
  });

  it('shows a plan execution with its step runs', async () => {
    tokenStore.set(TOKEN);
    fakeApi({
      'GET /me': () => ({ body: { name: 'operator', roles: ['OPERATOR', 'VIEWER'] } }),
      'GET /plan-executions/exec-1': () => ({ body: {
        id: 'exec-1aaaaaaa', taskId: 'task-1', modelCallId: 'call-1', status: 'RUNNING', requestedBy: 'operator', planDigest: 'd'.repeat(64),
        errorCode: null, createdAt: '2026-10-23T00:00:00Z', finishedAt: null, steps: [
          { sequence: 1, tool: 'DEMO_ANALYZE', toolVersion: 1, target: 'local-demo', timeoutSeconds: 30, status: 'COMPLETED', runId: 'run-aaaaaaaa', runStatus: 'COMPLETED' },
          { sequence: 2, tool: 'DEMO_REVIEW', toolVersion: 1, target: 'local-demo', timeoutSeconds: 30, status: 'LAUNCHED', runId: 'run-bbbbbbbb', runStatus: 'WAITING_APPROVAL' },
        ] } }),
    });
    renderApp('/executions/exec-1');
    expect(await screen.findByText('WAITING APPROVAL')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel execution' })).toBeInTheDocument();
    expect(screen.getByText(/A step is waiting for approval/)).toBeInTheDocument();
  });
});

describe('languages', () => {
  it('shows the console in Chinese and switches back to English', async () => {
    tokenStore.set(TOKEN);
    fakeApi({
      'GET /me': () => ({ body: { name: 'operator', roles: ['OPERATOR', 'VIEWER'] } }),
      'GET /agent-tasks': () => ({ body: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
    });
    render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <LanguageProvider language="zh"><SessionProvider><App /></SessionProvider></LanguageProvider></MemoryRouter>);
    expect(await screen.findByRole('link', { name: '测试项目' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '质量' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '退出' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'English' }));
    expect(await screen.findByRole('link', { name: 'Test projects' })).toBeInTheDocument();
    expect(localStorage.getItem('eahp.language')).toBe('en');
    await userEvent.click(screen.getByRole('button', { name: '中文' }));
    expect(await screen.findByRole('link', { name: '任务' })).toBeInTheDocument();
    localStorage.removeItem('eahp.language');
  });

  it('fills placeholders and falls back to English for unknown text', () => {
    let seen = '';
    function Probe() {
      const tr = useT();
      seen = [tr('{n} affected modules only', { n: 3 }), tr('Not in the dictionary')].join(' | ');
      return null;
    }
    render(<LanguageProvider language="zh"><Probe /></LanguageProvider>);
    expect(seen).toBe('仅 3 个受影响模块 | Not in the dictionary');
  });
});
