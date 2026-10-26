import { AdminPage } from './pages/AdminPage';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { ApprovalsPage } from './pages/ApprovalsPage';
import { ExecutionPage } from './pages/ExecutionPage';
import { QaProjectsPage } from './pages/QaProjectsPage';
import { PullRequestsPage } from './pages/PullRequestsPage';
import { QualityPage } from './pages/QualityPage';
import { LoginPage, SsoCallback } from './pages/LoginPage';
import { RunPage } from './pages/RunPage';
import { TaskDetailPage } from './pages/TaskDetailPage';
import { TasksPage } from './pages/TasksPage';
import { TestCasesPage } from './pages/TestCasesPage';
import { NewTaskPage } from './pages/NewTaskPage';
import { NewProjectPage } from './pages/NewProjectPage';
import { NewSchedulePage } from './pages/NewSchedulePage';
import { NewEnvironmentPage } from './pages/NewEnvironmentPage';
import { NewTokenPage } from './pages/NewTokenPage';
import { NewNotificationPage } from './pages/NewNotificationPage';
import { NewCodeRepositoryPage } from './pages/NewCodeRepositoryPage';
import { GenerateTestsPage } from './pages/GenerateTestsPage';
import { AiDraftsPage } from './pages/AiDraftsPage';
import { AiDraftPage } from './pages/AiDraftPage';
import { useSession } from './session';
import { LanguageSwitch, useT } from './i18n';

export function App() {
  const tr = useT();
  const { identity, loading, signOut, can } = useSession();
  if (loading) return <main className="login"><p className="muted">{tr('Loading…')}</p></main>;
  if (!identity) return window.location.pathname === '/auth/callback' ? <SsoCallback /> : <LoginPage />;

  return (
    <>
      <header className="topbar">
        <strong className="brand">{tr('EAHP Console')}</strong>
        <nav>
          <NavLink to="/" end>{tr('Tasks')}</NavLink>
          <NavLink to="/qa" end>{tr('Test projects')}</NavLink>
          <NavLink to="/qa/test-cases">{tr('Test cases')}</NavLink>
          <NavLink to="/qa/pull-requests">{tr('Pull requests')}</NavLink>
          <NavLink to="/qa/quality">{tr('Quality')}</NavLink>
          {can('APPROVER') && <NavLink to="/approvals">{tr('Approvals')}</NavLink>}
          {can('APPROVER') && <NavLink to="/admin">{tr('Administration')}</NavLink>}
        </nav>
        <div className="who">
          <span>{identity.name}</span>
          <span className="muted small">{identity.roles.join(' · ')}</span>
          <LanguageSwitch />
          <button className="secondary" onClick={signOut}>{tr('Sign out')}</button>
        </div>
      </header>
      <main className="content">
        <Routes>
          <Route path="/" element={<TasksPage />} />
          <Route path="/tasks/new" element={can('OPERATOR') ? <NewTaskPage /> : <Navigate to="/" replace />} />
          <Route path="/tasks/:id" element={<TaskDetailPage />} />
          <Route path="/runs/:id" element={<RunPage />} />
          <Route path="/qa" element={<QaProjectsPage />} />
          <Route path="/qa/projects/new" element={can('APPROVER') ? <NewProjectPage /> : <Navigate to="/qa" replace />} />
          <Route path="/qa/projects/:project/schedules/new" element={can('APPROVER') ? <NewSchedulePage /> : <Navigate to="/qa" replace />} />
          <Route path="/qa/projects/:project/environments/new" element={can('APPROVER') ? <NewEnvironmentPage /> : <Navigate to="/qa" replace />} />
          <Route path="/qa/test-cases" element={<TestCasesPage />} />
          <Route path="/qa/test-cases/generate" element={can('OPERATOR') ? <GenerateTestsPage /> : <Navigate to="/qa/test-cases" replace />} />
          <Route path="/qa/test-cases/drafts" element={<AiDraftsPage />} />
          <Route path="/qa/test-cases/:project/drafts/:id" element={<AiDraftPage />} />
          <Route path="/qa/pull-requests" element={<PullRequestsPage />} />
          <Route path="/qa/quality" element={<QualityPage />} />
          <Route path="/executions/:id" element={<ExecutionPage />} />
          <Route path="/approvals" element={can('APPROVER') ? <ApprovalsPage /> : <Navigate to="/" replace />} />
          <Route path="/admin" element={can('APPROVER') ? <AdminPage /> : <Navigate to="/" replace />} />
          <Route path="/admin/tokens/new" element={can('APPROVER') ? <NewTokenPage /> : <Navigate to="/" replace />} />
          <Route path="/admin/notifications/new" element={can('APPROVER') ? <NewNotificationPage /> : <Navigate to="/" replace />} />
          <Route path="/admin/code-repositories/new" element={can('APPROVER') ? <NewCodeRepositoryPage /> : <Navigate to="/" replace />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </>
  );
}
