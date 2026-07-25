import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useAuth } from './hooks/useAuth'
import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import QueuePage from './pages/QueuePage'
import RequestDetailPage from './pages/RequestDetailPage'
import RulesPage from './pages/RulesPage'
import HelpPage from './pages/HelpPage'
import Layout from './components/Layout'
import { canViewQueue } from './lib/permissions'

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

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
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
          <Route path="help" element={<HelpPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
