import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { api, tokenStore } from '../api/client';
import { SessionProvider } from '../session';
import { fakeApi } from './fakeApi';

const TOKEN = 'q'.repeat(40);
const page = <T,>(items: T[]) => ({ items, page: 0, size: 20, totalElements: items.length, totalPages: 1 });
const operator = { name: 'operator', roles: ['OPERATOR', 'VIEWER'] };
const suite = (key: string, dockerAccess: boolean) => ({
  projectKey: 'online-shop', key, name: key === 'backend-unit' ? 'Backend unit' : 'Backend integration', description: `${key} description`,
  runnerImage: 'maven:3.9.11-eclipse-temurin-17', command: ['mvn', 'verify'], reportDirs: ['target/surefire-reports'],
  network: 'egress', dockerAccess, maxTimeoutSeconds: dockerAccess ? 2400 : 1800, enabled: true,
});
let triageBody: object = {};
const projects = [{ key: 'online-shop', name: 'Online Shop', repositoryUrl: 'https://github.com/exedeehuashan/online-shop.git', defaultRef: 'main', enabled: true,
  suites: [suite('backend-integration', true), suite('backend-unit', false)], repositorySuitesEnabled: true, suitesFile: '.eahp/qa-suites.json',
  allowedImages: ['maven:3.9.11-eclipse-temurin-17'], allowDockerAccess: true, allowNetworkEgress: true, maxTimeoutSeconds: 2400 }];
const definition = (key: string, dockerAccess: boolean, source: 'registry' | 'repository' = 'registry') => ({
  source, key, name: key === 'backend-unit' ? 'Backend unit' : key === 'smoke' ? 'Smoke' : 'Backend integration', description: `${key} description`,
  image: 'maven:3.9.11-eclipse-temurin-17', command: ['mvn', 'verify'], reportDirs: ['target/surefire-reports'], network: 'egress', dockerAccess,
  maxTimeoutSeconds: dockerAccess ? 2400 : 1800 });
const catalogFor = (ref: string, extra: object[] = [], file = 'absent', error: string | null = null) => ({ project: 'online-shop', ref, commit: 'c'.repeat(40),
  repositoryFile: file, repositoryError: error, suites: [definition('backend-integration', true), definition('backend-unit', false), ...extra] });
const task = { id: 'task-1', title: 'Regression', description: null, status: 'PENDING', createdAt: '2026-10-23T00:00:00Z', updatedAt: '2026-10-23T00:00:00Z', version: 0 };
const tools = [
  { name: 'DEMO_ANALYZE', version: 1, target: 'local-demo', enabled: true, requiresApproval: false, retryable: true },
  { name: 'QA_RUN_SUITE', version: 1, target: 'qa-runner', enabled: true, requiresApproval: false, retryable: false },
];

function renderApp(path: string) {
  return render(<MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><SessionProvider><App /></SessionProvider></MemoryRouter>);
}
function taskRoutes(extra: Record<string, Parameters<typeof fakeApi>[0][string]> = {}) {
  return {
    'GET /me': () => ({ body: operator }),
    'GET /agent-tasks/task-1': () => ({ body: task, headers: { ETag: '"0"' } }),
    'GET /agent-tasks/task-1/runs': () => ({ body: page([]) }),
    'GET /agent-tasks/task-1/plans': () => ({ body: page([]) }),
    'GET /agent-tasks/task-1/plan-executions': () => ({ body: page([]) }),
    'GET /tools': () => ({ body: tools }),
    'GET /qa/projects': () => ({ body: projects }),
    'GET /qa/projects/online-shop/suites': (c: { path: string }) => ({ body: catalogFor(new URLSearchParams(c.path.split('?')[1] ?? '').get('ref') ?? 'main') }),
    ...extra,
  };
}

afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });

describe('QA console', () => {
  it('downloads artifacts with the Authorization header, not a token in the URL', async () => {
    tokenStore.set(TOKEN);
    const calls = fakeApi({ 'GET /artifacts/a-1/content': () => ({ body: { outcome: 'PASSED' } }) });
    expect((await api.qaSummary('a-1')).outcome).toBe('PASSED');
    expect((await api.artifactBlob('a-1')).size).toBeGreaterThan(0);
    for (const call of calls) {
      expect(call.headers.Authorization).toBe(`Bearer ${TOKEN}`);
      expect(call.path).not.toContain(TOKEN);
    }
  });

  it('runs a Docker-access suite through the approval-gated tool with the chosen ref', async () => {
    tokenStore.set(TOKEN);
    const calls = fakeApi(taskRoutes({ 'POST /agent-tasks/task-1/runs': () => ({ status: 202, body: { id: 'run-1' } }) }));
    renderApp('/tasks/task-1');
    const form = (await screen.findByRole('heading', { name: 'Run test suite' })).closest('section')!;
    await within(form).findByRole('option', { name: /Backend integration/ });
    await userEvent.selectOptions(within(form).getByLabelText('Suite'), 'backend-integration');
    expect(within(form).getByText(/an approver must approve the exact commit/)).toBeInTheDocument();
    await userEvent.type(within(form).getByLabelText('Branch, tag or commit'), 'feature/cancel');
    await userEvent.tab();
    expect(await within(form).findByText(/feature\/cancel →/)).toBeInTheDocument();
    await userEvent.click(within(form).getByRole('button', { name: 'Run suite' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST')).toBe(true));
    const start = calls.find(c => c.method === 'POST')!;
    expect(start.headers['Idempotency-Key']).toMatch(/^qa-/);
    expect(start.body).toEqual({ tool: 'QA_RUN_PRIVILEGED_SUITE', timeoutSeconds: 1800,
      arguments: { project: 'online-shop', suite: 'backend-integration', ref: 'feature/cancel' } });
  });

  it('runs an isolated suite through the normal tool and omits an empty ref', async () => {
    tokenStore.set(TOKEN);
    const calls = fakeApi(taskRoutes({ 'POST /agent-tasks/task-1/runs': () => ({ status: 202, body: { id: 'run-1' } }) }));
    renderApp('/tasks/task-1');
    const form = (await screen.findByRole('heading', { name: 'Run test suite' })).closest('section')!;
    expect((await within(form).findByLabelText('Suite') as HTMLSelectElement).value).toBe('backend-unit');
    await userEvent.click(within(form).getByRole('button', { name: 'Run suite' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST')).toBe(true));
    expect(calls.find(c => c.method === 'POST')!.body).toEqual({ tool: 'QA_RUN_SUITE', timeoutSeconds: 1800,
      arguments: { project: 'online-shop', suite: 'backend-unit' } });
    const generic = screen.getByRole('heading', { name: 'Runs' }).closest('section')!;
    expect(within(generic).queryByRole('option', { name: /QA_RUN_SUITE/ })).not.toBeInTheDocument();
  });

  it('labels repository-declared suites and reports an invalid suites file', async () => {
    tokenStore.set(TOKEN);
    let invalid = false;
    fakeApi(taskRoutes({ 'GET /qa/projects/online-shop/suites': () => ({ body: invalid
      ? catalogFor('main', [], 'invalid', 'invalid suites file: not valid JSON')
      : catalogFor('main', [definition('smoke', false, 'repository')], 'found') }) }));
    const { unmount } = renderApp('/tasks/task-1');
    const form = (await screen.findByRole('heading', { name: 'Run test suite' })).closest('section')!;
    expect(await within(form).findByRole('option', { name: 'Smoke [repo]' })).toBeInTheDocument();
    expect(within(form).getByText(/suites from/)).toBeInTheDocument();
    unmount();
    invalid = true;
    renderApp('/tasks/task-1');
    expect(await screen.findByText(/is invalid at this commit/)).toBeInTheDocument();
    expect(screen.getByText('invalid suites file: not valid JSON', { exact: false })).toBeInTheDocument();
  });

  it('shows the test result and failing tests from summary.json', async () => {
    tokenStore.set(TOKEN);
    triageBody = { runId: 'run-12345678', ingested: true, outcome: 'TESTS_FAILED', category: 'ENVIRONMENT_DOCKER',
      explanation: 'The test needs Docker (Testcontainers) but ran in a suite without Docker access.', tests: 43, failed: 1, error: null,
      failures: [{ testClass: 'IdentityPersistenceTest', testName: '', status: 'ERROR', failureType: 'java.lang.IllegalStateException',
        message: 'Could not find a valid Docker environment', category: 'ENVIRONMENT_DOCKER', explanation: 'needs Docker',
        history: { label: 'NEW_FAILURE', previousStatus: 'PASSED', previousCommit: 'a'.repeat(40), lastPassedCommit: 'a'.repeat(40), recentRuns: 4, recentFailures: 1 } }] };
    const run = { id: 'run-12345678', taskId: 'task-1', tool: 'QA_RUN_SUITE', status: 'FAILED', snapshotTitle: 'Regression', snapshotDescription: null,
      timeoutSeconds: 1800, createdAt: '2026-10-23T00:00:00Z', startedAt: null, finishedAt: null, deadlineAt: '2026-10-23T00:30:00Z',
      attemptCount: 1, cancelRequested: false, errorCode: 'TESTS_FAILED', steps: [], toolVersion: 1, target: 'qa-runner', inputDigest: 'd'.repeat(64),
      approvalId: null, arguments: { project: 'online-shop', suite: 'backend-unit', ref: 'main', commit: '39a6a0b55eba4924b2094505bcbadadb475fcff8' } };
    const summary = { project: 'online-shop', suite: 'backend-unit', ref: 'main', commit: run.arguments.commit, runnerImage: 'maven:3.9.11-eclipse-temurin-17',
      command: ['mvn', 'verify'], dockerAccess: false, exitCode: 1, stopped: false, timedOut: false, outcome: 'TESTS_FAILED',
      startedAt: '2026-10-23T00:00:00Z', finishedAt: '2026-10-23T00:01:26Z', durationSeconds: 86, tests: 43, failures: 0, errors: 1, skipped: 0,
      reportFiles: 15, unreadableReportFiles: 0, reportsTruncated: false,
      failedTests: [{ suite: 'IdentityPersistenceTest', name: '', kind: 'error', message: 'Could not find a valid Docker environment' }] };
    fakeApi({
      'GET /me': () => ({ body: operator }),
      'GET /agent-runs/run-12345678': () => ({ body: run }),
      'GET /agent-runs/run-12345678/invocations': () => ({ body: [] }),
      'GET /agent-runs/run-12345678/artifacts': () => ({ body: [
        { id: 'sum', runId: run.id, invocationId: 'i', attempt: 1, name: 'summary.json', mediaType: 'application/json', sizeBytes: 900, sha256: 'a'.repeat(64), createdAt: '' },
        { id: 'log', runId: run.id, invocationId: 'i', attempt: 1, name: 'console.log', mediaType: 'text/plain', sizeBytes: 30000, sha256: 'b'.repeat(64), createdAt: '', expiredAt: '2026-10-24T03:30:00Z' }] }),
      'GET /artifacts/sum/content': () => ({ body: summary }),
      'GET /qa/runs/run-12345678/triage': () => ({ body: triageBody }),
    });
    renderApp('/runs/run-12345678');
    expect(await screen.findByText('TESTS FAILED')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /online-shop\/backend-unit @ 39a6a0b/ })).toBeInTheDocument();
    expect(screen.getByText('43')).toBeInTheDocument();
    expect(await screen.findByText('Why it failed: environment docker')).toBeInTheDocument();
    expect(screen.getByText(/needs Docker \(Testcontainers\)/)).toBeInTheDocument();
    expect(screen.getByText('IdentityPersistenceTest')).toBeInTheDocument();
    expect(screen.getByText('Could not find a valid Docker environment')).toBeInTheDocument();
    expect(screen.getByText('new failure')).toBeInTheDocument();
    expect(screen.getByText(/1\/4 recent runs failed/)).toBeInTheDocument();
    const artifacts = screen.getByRole('heading', { name: 'Artifacts' }).closest('section')!;
    expect(within(artifacts).getAllByRole('button', { name: 'Download' })).toHaveLength(1);
    expect(within(artifacts).getByText('expired')).toBeInTheDocument();
    expect(within(artifacts).getByText('29.3 KiB')).toBeInTheDocument();
  });

  it('runs an API test collection against a registered environment', async () => {
    tokenStore.set(TOKEN);
    const env = { projectKey: 'online-shop', key: 'uat', name: 'Local UAT', services: { 'order-api': 'http://host.docker.internal:18081' }, enabled: true };
    const calls = fakeApi(taskRoutes({
      'GET /qa/projects/online-shop/api-tests': () => ({ body: { project: 'online-shop', ref: 'main', commit: 'b'.repeat(40), file: 'found', error: null,
        collections: [{ key: 'order-api-smoke', name: 'Order API smoke', description: null, service: 'order-api', cases: 6 }], environments: [env] } }),
      'POST /agent-tasks/task-1/runs': () => ({ status: 202, body: { id: 'run-2' } }),
    }));
    renderApp('/tasks/task-1');
    const form = (await screen.findByRole('heading', { name: 'Run API tests' })).closest('section')!;
    expect(await within(form).findByRole('option', { name: 'Order API smoke (6 cases)' })).toBeInTheDocument();
    expect(within(form).getByText('Sends requests to order-api → http://host.docker.internal:18081.')).toBeInTheDocument();
    await userEvent.click(within(form).getByRole('button', { name: 'Run API tests' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST')).toBe(true));
    expect(calls.find(c => c.method === 'POST')!.body).toEqual({ tool: 'QA_RUN_API_TESTS', timeoutSeconds: 600,
      arguments: { project: 'online-shop', collection: 'order-api-smoke', environment: 'uat' } });
  });

  it('lists registered projects and marks Docker-access suites', async () => {
    tokenStore.set(TOKEN);
    fakeApi({ 'GET /me': () => ({ body: operator }), 'GET /qa/projects': () => ({ body: projects }), 'GET /qa/schedules': () => ({ body: [] }),
      'GET /qa/environments': () => ({ body: [{ projectKey: 'online-shop', key: 'uat', name: 'Local UAT', services: { 'order-api': 'http://host.docker.internal:18081' }, enabled: true }] }) });
    renderApp('/qa');
    expect(await screen.findByRole('heading', { name: 'Online Shop' })).toBeInTheDocument();
    expect(await screen.findByText('Local UAT')).toBeInTheDocument();
    expect(screen.getByText('http://host.docker.internal:18081')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save environment' })).not.toBeInTheDocument();
    expect(screen.getByText('Docker · approval')).toBeInTheDocument();
    expect(screen.getByText('isolated')).toBeInTheDocument();
    expect(screen.getByText('enabled')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Test projects' })).toBeInTheDocument();
  });
});

describe('pull request runs', () => {
  it('shows poll health, trigger outcomes and links to runs', async () => {
    tokenStore.set(TOKEN);
    const row = (over: Record<string, unknown>) => ({ id: String(over.id), projectKey: 'online-shop', prNumber: 47, headSha: 'a'.repeat(40), suiteKey: 'backend-unit',
      prTitle: 'feat(catalog): expand schema', prUrl: 'https://github.com/exedeehuashan/online-shop/pull/47', headRef: 'feat/x', status: 'STARTED',
      skipReason: null, taskId: 'task-1', runId: 'run-1', runStatus: 'COMPLETED', runErrorCode: null, createdAt: '2026-10-23T00:00:00Z', startedAt: null, ...over });
    fakeApi({
      'GET /me': () => ({ body: operator }),
      'GET /qa/github-polls': () => ({ body: [{ projectKey: 'online-shop', lastPolledAt: '2026-10-23T00:00:00Z', lastSuccessAt: '2026-10-23T00:00:00Z', openPullRequests: 2, lastError: null }] }),
      'GET /qa/pull-requests/r1/impact': () => ({ body: { projectKey: 'online-shop', prNumber: 47, baseSha: 'f'.repeat(40), headSha: 'a'.repeat(40),
        computedAt: '2026-10-23T00:00:00Z', error: null, impact: { buildSystem: 'maven', changedFileCount: 2, changedFiles: ['core/A.java', 'api/B.java'],
          truncated: false, wholeBuild: false, testClasses: ['ApiTest'], filesOutsideModules: [],
          notes: ['core changed but has no test classes of its own; it is only covered through dependent modules, if at all.'],
          modules: [{ dir: 'api', coordinates: 'g:api', reason: 'DEPENDS_ON_CHANGED', changedFiles: 0, testClasses: 1 },
            { dir: 'core', coordinates: 'g:core', reason: 'CHANGED', changedFiles: 1, testClasses: 0 }] } } }),
      'GET /qa/pull-requests': () => ({ body: [
        row({ id: 'r1', selection: 'MODULES', selectionReason: 'AFFECTED_MODULES', selectedModules: ['api', 'core'] }),
        row({ id: 'r3', prNumber: 49, headSha: 'c'.repeat(40), status: 'SKIPPED', skipReason: 'NOT_AFFECTED', selection: 'NOT_AFFECTED', taskId: null, runId: null, runStatus: null }),
        row({ id: 'r4', prNumber: 50, headSha: 'd'.repeat(40), selection: 'FULL', selectionReason: 'BUILD_CHANGED', runId: 'run-4', runStatus: 'RUNNING' }),
        row({ id: 'r2', prNumber: 48, headSha: 'b'.repeat(40), status: 'SKIPPED', skipReason: 'FORK_NOT_TRUSTED', taskId: null, runId: null, runStatus: null }),
      ] }),
    });
    renderApp('/qa/pull-requests');
    expect(await screen.findByText('2 open pull requests')).toBeInTheDocument();
    expect(screen.getByText('From a fork: not run automatically')).toBeInTheDocument();
    expect(screen.getByText('2 affected modules only')).toHaveAttribute('title', 'api\ncore');
    expect(screen.getByText('Change does not affect this suite: not needed')).toBeInTheDocument();
    expect(screen.getByText('full suite: build files changed')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'online-shop #47' })).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByRole('link', { name: 'COMPLETED' })).toHaveAttribute('href', '/runs/run-1');
    expect(screen.getByRole('link', { name: 'Pull requests' })).toBeInTheDocument();

    await userEvent.click(screen.getAllByRole('button', { name: 'Impact' })[0]);
    expect(await screen.findByText(/2 changed files; 2 affected modules; 1 test classes in scope/)).toBeInTheDocument();
    expect(screen.getByText('depends on a change')).toBeInTheDocument();
    expect(screen.getByText('none')).toBeInTheDocument();
    expect(screen.getByText(/core changed but has no test classes/)).toBeInTheDocument();
  });
});

describe('quality page', () => {
  it('shows pass rate, failing and flaky tests, PR gates and checks a commit', async () => {
    tokenStore.set(TOKEN);
    const calls = fakeApi({
      'GET /me': () => ({ body: operator }),
      'GET /qa/projects': () => ({ body: projects }),
      'GET /qa/projects/online-shop/dashboard': () => ({ body: {
        project: 'online-shop', days: 14, runs: 8, passed: 4, passRate: 50, gate: { requiredSuites: ['backend-unit'] },
        suites: [{ suite: 'backend-unit', runs: 7, passed: 3, passRate: 42.9, lastOutcome: 'TESTS_FAILED', lastCommit: '1b228a0'.padEnd(40, '0'),
          lastAt: '2026-10-23T11:00:00Z', daily: [{ day: '2026-10-23', runs: 7, passed: 3 }] }],
        failingNow: [{ suite: 'backend-unit', testClass: 'com.example.CatalogMigrationTest', testName: '', category: 'ENVIRONMENT_DOCKER',
          consecutiveFailures: 3, failingSince: '4dfe646'.padEnd(40, '0'), failingSinceAt: '2026-10-23T10:00:00Z' }],
        flaky: [{ suite: 'backend-unit', testClass: 'com.example.OrderTest', testName: 'creates', flakyCommits: 1 }],
        slowest: [{ suite: 'backend-unit', testClass: 'com.example.ArchitectureTest', testName: 'layers', averageMs: 1002, runs: 4 }],
        pullRequests: [{ prNumber: 50, title: 'catalog: backfill', url: 'https://github.com/exedeehuashan/online-shop/pull/50', headSha: '1b228a0'.padEnd(40, '0'),
          gate: 'FAIL', affectedModules: 4, impactNotes: 1 }] } }),
      'GET /qa/projects/online-shop/gate': () => ({ body: { project: 'online-shop', commit: '1ca9ae5'.padEnd(40, '0'), verdict: 'FAIL',
        suites: [], reasons: ['backend-unit failed (TESTS_FAILED, ENVIRONMENT_DOCKER).'], notes: ['Already failing in backend-unit: CatalogMigrationTest.'],
        policy: { requiredSuites: ['backend-unit'] } } }),
      'GET /qa/quarantines': () => ({ body: [] }),
      'GET /qa/projects/online-shop/compare': () => ({ body: [
        { suite: 'backend-unit', status: 'COMPARED', base: { runId: 'r1', outcome: 'PASSED', tests: 42, scope: 'FULL' }, head: { runId: 'r2', outcome: 'TESTS_FAILED', tests: 43, scope: 'FULL' },
          sameScope: true, newFailures: ['com.example.OrderTest#creates'], fixed: ['src/cart.test.tsx#totals', 'com.example.A#b'], stillFailing: [], added: ['com.example.OrderTest#cancels'], removed: [],
          newFailureCount: 1, fixedCount: 2, stillFailingCount: 0, addedCount: 1, removedCount: 0 },
        { suite: 'frontend-unit', status: 'NO_BASE_RESULT', base: null, head: { runId: 'r3', outcome: 'PASSED', tests: 46, scope: 'FULL' }, sameScope: false,
          newFailures: [], fixed: [], stillFailing: [], added: [], removed: [], newFailureCount: 0, fixedCount: 0, stillFailingCount: 0, addedCount: 0, removedCount: 0 },
      ] }),
      'GET /qa/projects/online-shop/report': () => ({ body: { project: 'online-shop', ref: '1ca9ae5'.padEnd(40, '0'), commit: '1ca9ae5'.padEnd(40, '0'), verdict: 'FAIL',
        generatedAt: '2026-10-24T00:00:00Z', markdown: '## EAHP QA report: online-shop @ `1ca9ae500000`\n\n**Release gate: FAIL**\n' } }),
    });
    renderApp('/qa/quality');
    expect(await screen.findByText('50%')).toBeInTheDocument();
    expect(screen.getByText('4 of 8 runs')).toBeInTheDocument();
    expect(screen.getByText('CatalogMigrationTest')).toBeInTheDocument();
    expect(screen.getByText('environment docker')).toBeInTheDocument();
    expect(screen.getByText('3 runs')).toBeInTheDocument();
    expect(screen.getByText('OrderTest#creates')).toBeInTheDocument();
    expect(screen.getByText('1.00 s')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'backend-unit daily pass rate' })).toBeInTheDocument();
    expect(screen.getByText('2026-10-23: 3 of 7 runs passed (43%)')).toBeInTheDocument();
    expect(screen.getAllByText('FAIL').length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: 'Check gate' }));
    expect(await screen.findByText('backend-unit failed (TESTS_FAILED, ENVIRONMENT_DOCKER).')).toBeInTheDocument();
    expect(screen.getByText('Already failing in backend-unit: CatalogMigrationTest.')).toBeInTheDocument();
    expect(calls.find(c => c.path.startsWith('/qa/projects/online-shop/gate'))?.path).toBe('/qa/projects/online-shop/gate?ref=main');
    await userEvent.click(screen.getByRole('button', { name: 'QA report' }));
    expect((await screen.findByLabelText('QA report Markdown')).textContent).toContain('**Release gate: FAIL**');
    expect(calls.find(c => c.path.startsWith('/qa/projects/online-shop/report'))?.path).toBe(`/qa/projects/online-shop/report?ref=${'1ca9ae5'.padEnd(40, '0')}`);
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    await userEvent.click(screen.getByRole('button', { name: 'Copy Markdown' }));
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('## EAHP QA report'));
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Head'), 'feat/x');
    await userEvent.click(screen.getByRole('button', { name: 'Compare' }));
    expect(await screen.findByText(/1 new failure, 2 fixed, 0 still failing, 1 added, 0 removed/)).toBeInTheDocument();
    expect(within(screen.getByText('New failures (1)').parentElement!).getByText('OrderTest#creates')).toBeInTheDocument();
    expect(screen.getByText('src/cart.test.tsx#totals')).toBeInTheDocument();
    expect(screen.getByText('No result at the base commit to compare with.')).toBeInTheDocument();
    expect(calls.find(c => c.path.startsWith('/qa/projects/online-shop/compare'))?.path).toBe('/qa/projects/online-shop/compare?base=main&head=feat%2Fx');
    expect(screen.getByRole('link', { name: 'Quality' })).toBeInTheDocument();
    expect(screen.getByText('No test is quarantined.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Quarantine' })).not.toBeInTheDocument();
  });

  it('lets approvers quarantine a failing test with a reason and release it', async () => {
    tokenStore.set(TOKEN);
    const failing = { suite: 'frontend-unit', testClass: 'src/cart.test.tsx', testName: 'totals', category: 'ASSERTION', consecutiveFailures: 2, failingSince: null, failingSinceAt: null };
    let active: unknown[] = [];
    const calls = fakeApi({
      'GET /me': () => ({ body: { name: 'admin', roles: ['APPROVER', 'OPERATOR', 'VIEWER'] } }),
      'GET /qa/projects': () => ({ body: projects }),
      'GET /qa/projects/online-shop/dashboard': () => ({ body: { project: 'online-shop', days: 14, runs: 1, passed: 0, passRate: 0, gate: null,
        suites: [], failingNow: [failing], flaky: [], slowest: [], pullRequests: [] } }),
      'GET /qa/quarantines': () => ({ body: active }),
      'POST /qa/admin/quarantines': () => {
        active = [{ id: 'q1', projectKey: 'online-shop', suiteKey: 'frontend-unit', testClass: 'src/cart.test.tsx', testName: 'totals',
          reason: 'Rounding bug tracked in issue 7', createdBy: 'admin', createdAt: '2026-10-24T00:00:00Z', expiresAt: '2026-10-31T00:00:00Z',
          releasedAt: null, releasedBy: null, active: true }];
        return { status: 201, body: active[0] };
      },
      'POST /qa/admin/quarantines/q1/release': () => { active = []; return { body: {} }; },
    });
    renderApp('/qa/quality');
    await userEvent.selectOptions(await screen.findByLabelText('Failing test'), 'frontend-unit: src/cart.test.tsx#totals');
    await userEvent.type(screen.getByLabelText('Reason'), 'Rounding bug tracked in issue 7');
    await userEvent.clear(screen.getByLabelText('Days'));
    await userEvent.type(screen.getByLabelText('Days'), '7');
    await userEvent.click(screen.getByRole('button', { name: 'Quarantine' }));
    expect(await screen.findByText('Rounding bug tracked in issue 7')).toBeInTheDocument();
    expect(calls.find(c => c.method === 'POST' && c.path === '/qa/admin/quarantines')?.body).toEqual({ project: 'online-shop', suite: 'frontend-unit',
      testClass: 'src/cart.test.tsx', testName: 'totals', reason: 'Rounding bug tracked in issue 7', days: 7 });
    expect(screen.queryByLabelText('Failing test')).not.toBeInTheDocument();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await userEvent.click(screen.getByRole('button', { name: 'Release' }));
    expect(await screen.findByText('No test is quarantined.')).toBeInTheDocument();
  });
});

const storage = { tableBytes: { run_artifacts: 581632 }, liveArtifacts: 12, liveArtifactBytes: 43008, expiredArtifacts: 3, artifactDays: 30, testResultDays: 180,
  lastRun: { id: 1, actor: 'retention', startedAt: '2026-10-24T03:30:00Z', artifactsExpired: 3, bytesFreed: 2048, testResultsDeleted: 0 } };

describe('project administration', () => {
  it('lets approvers register projects and toggle triggers, and hides it from operators', async () => {
    tokenStore.set(TOKEN);
    const admin = { name: 'admin', roles: ['APPROVER', 'OPERATOR', 'VIEWER'] };
    const projectAdmin = { project: projects[0], triggers: [{ suite: 'backend-unit', enabled: true }, { suite: 'backend-integration', enabled: false }], gateSuites: ['backend-unit'] };
    const calls = fakeApi({
      'GET /me': () => ({ body: admin }),
      'GET /qa/projects': () => ({ body: projects }),
      'GET /qa/admin/projects/online-shop': () => ({ body: projectAdmin }),
      'GET /qa/admin/events': () => ({ body: [{ id: 1, actor: 'admin', action: 'GATE_SET', projectKey: 'online-shop', subject: null, before: null, after: ['backend-unit'], occurredAt: '2026-10-24T00:00:00Z' }] }),
      'POST /qa/admin/projects': () => ({ status: 201, body: projectAdmin }),
      'PUT /qa/admin/projects/online-shop/triggers/backend-integration': () => ({ body: projectAdmin }),
      'GET /qa/schedules': () => ({ body: [] }),
      'GET /admin/storage': () => ({ body: storage }),
      'POST /admin/retention/run': () => ({ body: storage.lastRun }),
      'GET /admin/webhooks': () => ({ body: [] }),
      'GET /admin/tokens': () => ({ body: [] }),
      'POST /admin/tokens': () => ({ status: 201, body: { secret: 'eahp_issued-once', token: { id: 't1', name: 'alice', description: null, roles: ['OPERATOR', 'VIEWER'],
        createdBy: 'admin', createdAt: '2026-10-24T00:00:00Z', expiresAt: null, lastUsedAt: null, revokedAt: null, revokedBy: null, active: true } } }),
      'POST /admin/webhooks': () => ({ status: 201, body: { secret: 'one-time-secret', subscription: { id: 'w1', name: 'Team chat', url: 'https://hooks.example.com/x',
        events: ['RUN_*'], enabled: true, cursorId: 0, consecutiveFailures: 0, nextAttemptAt: null, lastDeliveryAt: null, lastError: null, createdBy: 'admin', createdAt: '2026-10-24T00:00:00Z' } } }),
    });
    const { unmount } = renderApp('/qa');
    const form = (await screen.findByRole('heading', { name: 'Register a project' })).closest('section')!;
    await userEvent.type(within(form).getByLabelText('Key'), 'billing');
    await userEvent.type(within(form).getByLabelText('Name'), 'Billing');
    await userEvent.type(within(form).getByLabelText('Repository URL (https)'), 'https://github.com/example/billing.git');
    await userEvent.click(within(form).getByRole('button', { name: 'Register project' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST')).toBe(true));
    expect(calls.find(c => c.method === 'POST')!.body).toEqual({ key: 'billing', name: 'Billing', repositoryUrl: 'https://github.com/example/billing.git',
      defaultRef: 'main', allowedImages: ['maven:3.9.11-eclipse-temurin-17'] });

    await userEvent.click(screen.getByText('Administer Online Shop'));
    await userEvent.click(await screen.findByRole('checkbox', { name: 'backend-integration' }));
    await waitFor(() => expect(calls.some(c => c.method === 'PUT')).toBe(true));
    expect(calls.find(c => c.method === 'PUT')!.body).toEqual({ enabled: true });
    expect(screen.getByText('gate set')).toBeInTheDocument();
    expect(screen.getByText('42 KiB')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Apply retention now' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.path === '/admin/retention/run')).toBe(true));
    const hooks = screen.getByRole('heading', { name: 'Webhooks' }).closest('section')!;
    await userEvent.type(within(hooks).getByLabelText('Name'), 'Team chat');
    await userEvent.type(within(hooks).getByLabelText('URL'), 'https://hooks.example.com/x');
    await userEvent.clear(within(hooks).getByLabelText('Events'));
    await userEvent.type(within(hooks).getByLabelText('Events'), 'RUN_*');
    await userEvent.click(within(hooks).getByRole('button', { name: 'Add webhook' }));
    expect(await within(hooks).findByText('one-time-secret')).toBeInTheDocument();
    expect(calls.find(c => c.method === 'POST' && c.path === '/admin/webhooks')!.body).toEqual({ name: 'Team chat', url: 'https://hooks.example.com/x', events: ['RUN_*'] });
    const tokenForm = screen.getByRole('heading', { name: 'API tokens' }).closest('section')!;
    await userEvent.type(within(tokenForm).getByLabelText('Name'), 'alice');
    await userEvent.click(within(tokenForm).getByLabelText('operator'));
    await userEvent.click(within(tokenForm).getByRole('button', { name: 'Issue token' }));
    expect(await within(tokenForm).findByText('eahp_issued-once')).toBeInTheDocument();
    expect(calls.find(c => c.method === 'POST' && c.path === '/admin/tokens')!.body).toEqual({ name: 'alice', description: null, roles: ['OPERATOR'], expiresInDays: 90 });
    unmount();

    fakeApi({ 'GET /me': () => ({ body: operator }), 'GET /qa/projects': () => ({ body: projects }), 'GET /qa/schedules': () => ({ body: [] }) });
    renderApp('/qa');
    expect(await screen.findByRole('heading', { name: 'Online Shop' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Register a project' })).not.toBeInTheDocument();
    expect(screen.queryByText('Administer Online Shop')).not.toBeInTheDocument();
  });
});

describe('schedules', () => {
  it('shows schedules to everyone and lets approvers pause them', async () => {
    tokenStore.set(TOKEN);
    const schedule = { id: 's1', projectKey: 'online-shop', suiteKey: 'backend-unit', ref: 'main', kind: 'ON_CHANGE', cron: null, enabled: true, taskId: 't1',
      lastCommit: '1ca9ae5'.padEnd(40, '0'), lastRunId: 'run-9', lastRunStatus: 'FAILED', lastCheckedAt: '2026-10-24T00:00:00Z', lastError: null, nextRunAt: null };
    fakeApi({ 'GET /me': () => ({ body: operator }), 'GET /qa/projects': () => ({ body: projects }), 'GET /qa/schedules': () => ({ body: [schedule] }) });
    const { unmount } = renderApp('/qa');
    expect(await screen.findByText('when the ref changes')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'FAILED' })).toHaveAttribute('href', '/runs/run-9');
    expect(screen.queryByRole('button', { name: 'Pause' })).not.toBeInTheDocument();
    unmount();
    const admin = { name: 'admin', roles: ['APPROVER', 'OPERATOR', 'VIEWER'] };
    const calls = fakeApi({ 'GET /me': () => ({ body: admin }), 'GET /qa/projects': () => ({ body: projects }), 'GET /qa/schedules': () => ({ body: [schedule] }),
      'GET /qa/admin/projects/online-shop': () => ({ body: { project: projects[0], triggers: [], gateSuites: [] } }), 'GET /qa/admin/events': () => ({ body: [] }),
      'PUT /qa/admin/schedules/s1/enabled': () => ({ body: { ...schedule, enabled: false } }) });
    renderApp('/qa');
    await userEvent.click(await screen.findByRole('button', { name: 'Pause' }));
    await waitFor(() => expect(calls.some(c => c.method === 'PUT')).toBe(true));
    expect(calls.find(c => c.method === 'PUT')!.body).toEqual({ enabled: false });
  });
});
