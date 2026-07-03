import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useSse } from '../hooks/useSse'
import StatusBadge from '../components/StatusBadge'
import AppIcon from '../components/AppIcon'
import type { Stats, Page, CalloutRequest, TenantStatus } from '../types'

interface StatCardProps {
  label: string
  count: number
  color: string
  onClick: () => void
}

function StatCard({ label, count, color, onClick }: StatCardProps) {
  return (
    <button
      onClick={onClick}
      className={`flex flex-col items-start p-5 rounded-xl border-2 w-full text-left transition-all hover:shadow-md ${color}`}
    >
      <span className="text-3xl font-bold">{count}</span>
      <span className="text-sm font-medium mt-1">{label}</span>
    </button>
  )
}

function TenantStatusCard() {
  const [status, setStatus] = useState<TenantStatus | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    fetch('/api/config/status', { credentials: 'include' })
      .then(r => r.ok ? r.json() : Promise.reject(new Error(`Server error ${r.status}`)))
      .then((s: TenantStatus) => setStatus(s))
      .catch(() => setFailed(true))
  }, [])

  return (
    <div className="bg-white rounded-xl border border-gray-200 px-5 py-4 mb-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 sm:gap-4">
      <div className="min-w-0">
        <p className="text-xs font-medium uppercase tracking-wide text-gray-500">Omnissa Access Tenant</p>
        <p className="font-medium text-gray-900 break-all sm:break-normal sm:truncate">
          {failed ? 'Status unavailable' : status ? (status.tenantUrl || 'Not configured') : 'Checking…'}
        </p>
        {status?.error && !status.reachable && (
          <p className="text-xs text-gray-500 mt-0.5 truncate">{status.error}</p>
        )}
      </div>
      {status && !failed && (
        <div className="shrink-0 flex flex-col items-start sm:items-end gap-1">
          <span
            className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium
              ${status.reachable ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'}`}
          >
            <span className={`inline-block w-2 h-2 rounded-full ${status.reachable ? 'bg-green-500' : 'bg-red-500'}`} />
            {status.reachable ? 'Connected' : 'Unreachable'}
          </span>
          {status.checkedAt && (
            <span className="text-xs text-gray-400">Checked {formatDate(status.checkedAt)}</span>
          )}
        </div>
      )}
    </div>
  )
}

export default function DashboardPage() {
  const navigate = useNavigate()
  const [stats, setStats] = useState<Stats>({ pending: 0, approved: 0, rejected: 0, deactivated: 0 })
  const [recent, setRecent] = useState<CalloutRequest[]>([])

  const loadStats = useCallback(() => {
    fetch('/api/statistics/approvals', { credentials: 'include' })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (data?.approvalStates) setStats(data.approvalStates as Stats)
      })
  }, [])

  const loadRecent = useCallback(() => {
    fetch('/api/approvals/requests?state=pending&size=5', { credentials: 'include' })
      .then(r => r.ok ? r.json() : null)
      .then((p: Page<CalloutRequest> | null) => setRecent(p?.content ?? []))
  }, [])

  useEffect(() => { loadStats(); loadRecent() }, [loadStats, loadRecent])

  useSse(
    useCallback(() => { loadStats(); loadRecent() }, [loadStats, loadRecent]),
    useCallback(() => { loadStats(); loadRecent() }, [loadStats, loadRecent])
  )

  return (
    <div>
      <h1 className="text-2xl font-semibold text-gray-900 mb-6">Dashboard</h1>

      {/* Access tenant connectivity */}
      <TenantStatusCard />

      {/* Stat cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
        <StatCard
          label="Awaiting Review"
          count={stats.pending}
          color="border-amber-300 bg-amber-50 text-amber-800"
          onClick={() => navigate('/queue?state=pending')}
        />
        <StatCard
          label="Approved"
          count={stats.approved}
          color="border-green-300 bg-green-50 text-green-800"
          onClick={() => navigate('/queue?state=approved')}
        />
        <StatCard
          label="Rejected"
          count={stats.rejected}
          color="border-red-300 bg-red-50 text-red-800"
          onClick={() => navigate('/queue?state=rejected')}
        />
        <StatCard
          label="Deactivated"
          count={stats.deactivated}
          color="border-gray-300 bg-gray-50 text-gray-600"
          onClick={() => navigate('/queue?state=deactivated')}
        />
      </div>

      {/* Recent pending requests */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
          <h2 className="font-semibold text-gray-800">Awaiting Review</h2>
          <button
            onClick={() => navigate('/queue?state=pending')}
            className="text-sm text-omnissa hover:underline"
          >
            View all →
          </button>
        </div>
        {recent.length === 0 ? (
          <p className="text-sm text-gray-400 px-5 py-8 text-center">No pending requests.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {recent.map(req => (
              <li
                key={req.id}
                onClick={() => navigate(`/requests/${req.requestId}`)}
                className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-4 hover:bg-gray-50 cursor-pointer transition-colors"
              >
                <AppIcon resourceUuid={req.resourceUuid} resourceName={req.resourceName} size={40} />
                <div className="flex-1 min-w-[10rem]">
                  <p className="font-medium text-gray-900 truncate">{req.resourceName}</p>
                  <p className="text-sm text-gray-500 truncate">Requested by {req.userId}</p>
                </div>
                <div className="shrink-0 flex flex-col items-end gap-1">
                  <StatusBadge state={req.state} />
                  <span className="text-xs text-gray-400">{formatDate(req.receivedDate)}</span>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function formatDate(iso: string) {
  if (!iso) return ''
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
