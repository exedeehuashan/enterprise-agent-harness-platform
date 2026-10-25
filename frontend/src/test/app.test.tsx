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

describe('single sign-on', () => {
  const IDP = 'http://127.0.0.1:8180/realms/eahp';
  const discovery = { issuer: IDP, authorization_endpoint: `${IDP}/protocol/openid-connect/auth`, token_endpoint: `${IDP}/protocol/openid-connect/token` };

  it('starts the authorization code flow with PKCE from the login page', async () => {
    const { navigation } = await import('../sso');
    const assign = vi.fn();
    navigation.assign = assign;
    fakeApi({
      'GET /auth/config': () => ({ body: { oidc: { issuer: IDP, clientId: 'eahp-console', scope: 'openid profile' } } }),
      [`GET ${IDP}/.well-known/openid-configuration`]: () => ({ body: discovery }),
    });
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in with SSO' }));
    await waitFor(() => expect(assign).toHaveBeenCalled());
    const url = new URL(assign.mock.calls[0][0] as string);
    expect(url.origin + url.pathname).toBe(discovery.authorization_endpoint);
    expect(url.searchParams.get('client_id')).toBe('eahp-console');
    expect(url.searchParams.get('response_type')).toBe('code');
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(url.searchParams.get('redirect_uri')).toBe(`${window.location.origin}/auth/callback`);
    const pending = JSON.parse(sessionStorage.getItem('eahp.sso.pending')!);
    expect(url.searchParams.get('state')).toBe(pending.state);
  });

  it('exchanges the code on the callback page and signs in with the access token', async () => {
    sessionStorage.setItem('eahp.sso.pending', JSON.stringify({ verifier: 'v'.repeat(64), state: 'st4te', tokenEndpoint: discovery.token_endpoint, clientId: 'eahp-console' }));
    window.history.pushState(null, '', '/auth/callback?code=abc123&state=st4te');
    const calls = fakeApi({
      [`POST ${discovery.token_endpoint}`]: () => ({ body: { access_token: 'header.payload.signature', token_type: 'Bearer' } }),
      'GET /me': c => (c.headers.Authorization === 'Bearer header.payload.signature'
        ? { body: { name: 'sso:alice', roles: ['VIEWER', 'OPERATOR', 'APPROVER'] } } : { status: 401, body: {} }),
      'GET /agent-tasks': () => ({ body: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
    });
    renderApp('/');
    expect(await screen.findByText('sso:alice')).toBeInTheDocument();
    const exchange = calls.find(c => c.path === discovery.token_endpoint)!;
    expect(exchange.method).toBe('POST');
    const form = new URLSearchParams(exchange.body as string);
    expect(form.get('grant_type')).toBe('authorization_code');
    expect(form.get('code')).toBe('abc123');
    expect(form.get('code_verifier')).toBe('v'.repeat(64));
    expect(window.location.pathname).toBe('/');
    expect(sessionStorage.getItem('eahp.sso.pending')).toBeNull();
  });

  it('signing out also ends the provider session, so the next SSO sign-in asks again', async () => {
    const { navigation } = await import('../sso');
    const post = vi.fn();
    navigation.post = post;
    const endSession = `${IDP}/protocol/openid-connect/logout`;
    sessionStorage.setItem('eahp.sso.pending', JSON.stringify({ verifier: 'v'.repeat(64), state: 'st4te', tokenEndpoint: discovery.token_endpoint, endSession, clientId: 'eahp-console' }));
    window.history.pushState(null, '', '/auth/callback?code=abc123&state=st4te');
    fakeApi({
      [`POST ${discovery.token_endpoint}`]: () => ({ body: { access_token: 'header.payload.signature', id_token: 'id.token.value', token_type: 'Bearer' } }),
      'GET /me': () => ({ body: { name: 'sso:alice', roles: ['VIEWER'] } }),
      'GET /agent-tasks': () => ({ body: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
    });
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(post).toHaveBeenCalledWith(endSession, { client_id: 'eahp-console', post_logout_redirect_uri: `${window.location.origin}/`, id_token_hint: 'id.token.value' });
    expect(sessionStorage.getItem('eahp.sso.logout')).toBeNull();
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('rejects a callback whose state does not match', async () => {
    sessionStorage.setItem('eahp.sso.pending', JSON.stringify({ verifier: 'v', state: 'expected', tokenEndpoint: discovery.token_endpoint, clientId: 'eahp-console' }));
    window.history.pushState(null, '', '/auth/callback?code=abc&state=forged');
    fakeApi({});
    renderApp('/');
    expect(await screen.findByText(/did not match the request/)).toBeInTheDocument();
    window.history.pushState(null, '', '/');
  });
});
