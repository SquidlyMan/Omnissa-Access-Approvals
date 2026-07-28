import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useAuth } from './hooks/useAuth'
import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import QueuePage from './pages/QueuePage'
import RequestDetailPage from './pages/RequestDetailPage'
import RulesPage from './pages/RulesPage'
import HelpPage from './pages/HelpPage'
import UsersPage from './pages/UsersPage'
import Layout from './components/Layout'
import { canAdminister, canViewQueue } from './lib/permissions'

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return <div className="flex h-screen items-center justify-center text-gray-500">Loading…</div>
  if (!user) {
    window.location.href = '/login'
    return null
  }
  return <>{children}</>
}

/**
 * Pages that need live request data (#52). An auditor-only user has no access
 * to the queue, request details or rules — send them to the audit trail rather
 * than rendering a page whose every call comes back 403.
 */
function QueueRoute({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return <div className="flex h-screen items-center justify-center text-gray-500">Loading…</div>
  if (!canViewQueue(user)) return <Navigate to="/queue?state=audit" replace />
  return <>{children}</>
}

/**
 * Local account management (#58) is admin-only on the server. Send everyone
 * else back to their landing page rather than rendering a table whose every
 * call comes back 403. Self-service password change lives in the nav bar, so
 * no non-admin needs this route.
 */
function AdminRoute({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return <div className="flex h-screen items-center justify-center text-gray-500">Loading…</div>
  if (!canAdminister(user)) return <Navigate to="/" replace />
  return <>{children}</>
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* This is the only place client routes are declared. The server serves
            the SPA shell for any unmatched, non-API page request (SpaController),
            so a route added here survives a refresh or a direct link with no
            backend change. A path-based allow-list on an external reverse proxy
            is the one thing that can still 404 it — see docs/deployment.md. */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="dashboard" element={<QueueRoute><DashboardPage /></QueueRoute>} />
          <Route path="queue" element={<QueuePage />} />
          <Route path="requests/:requestId" element={<QueueRoute><RequestDetailPage /></QueueRoute>} />
          <Route path="rules" element={<QueueRoute><RulesPage /></QueueRoute>} />
          <Route path="users" element={<AdminRoute><UsersPage /></AdminRoute>} />
          <Route path="help" element={<HelpPage />} />
          {/* Unknown paths reach the client now that the server hands the shell
              to anything that is not an API call or an asset, so the router owns
              the mistyped URL. Without this it renders as a blank page inside
              the chrome, which reads as a broken app rather than a bad link. */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
