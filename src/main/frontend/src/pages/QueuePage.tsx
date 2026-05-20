import { useState, useEffect, useCallback } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useSse } from '../hooks/useSse'
import StatusBadge from '../components/StatusBadge'
import AppIcon from '../components/AppIcon'
import ApprovalDialog from '../components/ApprovalDialog'
import type { Page, CalloutRequest } from '../types'

const STATE_TABS = [
  { key: 'pending',     label: 'Awaiting Review' },
  { key: 'approved',    label: 'Approved' },
  { key: 'rejected',    label: 'Rejected' },
  { key: 'deactivated', label: 'Deactivated' },
]

export default function QueuePage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const activeState = searchParams.get('state') ?? 'pending'

  const [page, setPage] = useState<Page<CalloutRequest> | null>(null)
  const [pageNum, setPageNum] = useState(0)
  const [pendingLiveUpdate, setPendingLiveUpdate] = useState(false)
  const [dialogReq, setDialogReq] = useState<CalloutRequest | null>(null)

  const load = useCallback(() => {
    fetch(`/api/approvals/requests?state=${activeState}&page=${pageNum}&size=20&sort=id,desc`, {
      credentials: 'include',
    })
      .then(r => r.ok ? r.json() : null)
      .then(setPage)
      .finally(() => setPendingLiveUpdate(false))
  }, [activeState, pageNum])

  useEffect(() => { setPageNum(0) }, [activeState])
  useEffect(() => { load() }, [load])

  useSse(
    useCallback(() => { if (activeState === 'pending') setPendingLiveUpdate(true) }, [activeState]),
    useCallback(() => { load() }, [load])
  )

  function switchState(s: string) {
    setSearchParams({ state: s })
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold text-gray-900 mb-5">Queue</h1>

      {/* Live update banner */}
      {pendingLiveUpdate && (
        <div
          onClick={load}
          className="mb-4 cursor-pointer rounded-lg bg-amber-50 border border-amber-200 px-4 py-2 text-sm text-amber-800 font-medium flex items-center gap-2"
        >
          <span className="inline-block w-2 h-2 rounded-full bg-amber-500 animate-pulse" />
          New requests arrived — click to refresh
        </div>
      )}

      {/* Tab bar */}
      <div className="flex gap-1 mb-5 border-b border-gray-200">
        {STATE_TABS.map(tab => (
          <button
            key={tab.key}
            onClick={() => switchState(tab.key)}
            className={`px-4 py-2 text-sm font-medium rounded-t-lg transition-colors -mb-px border-b-2
              ${activeState === tab.key
                ? 'border-omnissa text-omnissa'
                : 'border-transparent text-gray-500 hover:text-gray-700'}`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Request list */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        {!page || page.content.length === 0 ? (
          <p className="text-sm text-gray-400 px-5 py-8 text-center">No requests in this category.</p>
        ) : (
          <>
            <ul className="divide-y divide-gray-100">
              {page.content.map(req => (
                <li
                  key={req.id}
                  className="flex items-center gap-4 px-5 py-4 hover:bg-gray-50 transition-colors"
                >
                  <AppIcon resourceUuid={req.resourceUuid} resourceName={req.resourceName} size={44} />
                  <div
                    className="flex-1 min-w-0 cursor-pointer"
                    onClick={() => navigate(`/requests/${req.requestId}`)}
                  >
                    <p className="font-medium text-gray-900 truncate">{req.resourceName}</p>
                    <p className="text-sm text-gray-500 truncate">
                      {req.userId} · {formatDate(req.receivedDate)}
                    </p>
                  </div>
                  <div className="shrink-0 flex items-center gap-3">
                    <StatusBadge state={req.state} />
                    {req.state === 'pending' && (
                      <button
                        onClick={() => setDialogReq(req)}
                        className="text-sm px-3 py-1.5 rounded-lg bg-omnissa text-white hover:bg-omnissa-dark transition-colors"
                      >
                        Review
                      </button>
                    )}
                  </div>
                </li>
              ))}
            </ul>

            {/* Pagination */}
            {page.totalPages > 1 && (
              <div className="flex items-center justify-between px-5 py-3 border-t border-gray-100 text-sm text-gray-500">
                <span>Page {page.number + 1} of {page.totalPages}</span>
                <div className="flex gap-2">
                  <button
                    disabled={page.number === 0}
                    onClick={() => setPageNum(p => p - 1)}
                    className="px-3 py-1 rounded border border-gray-200 disabled:opacity-40 hover:bg-gray-50"
                  >
                    ← Prev
                  </button>
                  <button
                    disabled={page.number + 1 >= page.totalPages}
                    onClick={() => setPageNum(p => p + 1)}
                    className="px-3 py-1 rounded border border-gray-200 disabled:opacity-40 hover:bg-gray-50"
                  >
                    Next →
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>

      {/* Approval dialog */}
      {dialogReq && (
        <ApprovalDialog
          requestId={dialogReq.requestId}
          resourceName={dialogReq.resourceName}
          onClose={() => setDialogReq(null)}
          onComplete={() => { setDialogReq(null); load() }}
        />
      )}
    </div>
  )
}

function formatDate(iso: string) {
  if (!iso) return ''
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
