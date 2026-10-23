import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { ApprovalsPage } from './pages/ApprovalsPage';
import { ExecutionPage } from './pages/ExecutionPage';
import { QaProjectsPage } from './pages/QaProjectsPage';
import { LoginPage } from './pages/LoginPage';
import { RunPage } from './pages/RunPage';
import { TaskDetailPage } from './pages/TaskDetailPage';
import { TasksPage } from './pages/TasksPage';
import { useSession } from './session';

export function App() {
  const { identity, loading, signOut, can } = useSession();
  if (loading) return <main className="login"><p className="muted">Loading…</p></main>;
  if (!identity) return <LoginPage />;

  return (
    <>
      <header className="topbar">
        <strong className="brand">EAHP Console</strong>
        <nav>
          <NavLink to="/" end>Tasks</NavLink>
          <NavLink to="/qa">Test projects</NavLink>
          {can('APPROVER') && <NavLink to="/approvals">Approvals</NavLink>}
        </nav>
        <div className="who">
          <span>{identity.name}</span>
          <span className="muted small">{identity.roles.join(' · ')}</span>
          <button className="secondary" onClick={signOut}>Sign out</button>
        </div>
      </header>
      <main className="content">
        <Routes>
          <Route path="/" element={<TasksPage />} />
          <Route path="/tasks/:id" element={<TaskDetailPage />} />
          <Route path="/runs/:id" element={<RunPage />} />
          <Route path="/qa" element={<QaProjectsPage />} />
          <Route path="/executions/:id" element={<ExecutionPage />} />
          <Route path="/approvals" element={can('APPROVER') ? <ApprovalsPage /> : <Navigate to="/" replace />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </>
  );
}
