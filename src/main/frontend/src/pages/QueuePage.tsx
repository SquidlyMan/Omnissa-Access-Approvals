import { useState, useEffect, useCallback } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useSse } from '../hooks/useSse'
import StatusBadge from '../components/StatusBadge'
import AppIcon from '../components/AppIcon'
import ApprovalDialog from '../components/ApprovalDialog'
import type { Page, CalloutRequest, AuditPage, AuditAction } from '../types'
import { getCsrfToken } from '../utils/csrf'

const STATE_TABS = [
  { key: 'pending',     label: 'Awaiting Review' },
  { key: 'approved',    label: 'Approved' },
  { key: 'rejected',    label: 'Rejected' },
  { key: 'deactivated', label: 'Deactivated' },
  { key: 'audit',       label: 'Audit' },
]

const AUDIT_ACTION_STYLES: Record<AuditAction, string> = {
  'approved':               'bg-green-100 text-green-800',
  'auto-approved':          'bg-green-100 text-green-800',
  'rejected':               'bg-red-100 text-red-800',
  'auto-rejected':          'bg-red-100 text-red-800',
  'request-received':       'bg-gray-100 text-gray-600',
  'deactivation-received':  'bg-gray-100 text-gray-600',
  'decision-undeliverable': 'bg-orange-100 text-orange-800',
}

function AuditActionBadge({ action }: { action: AuditAction }) {
  const style = AUDIT_ACTION_STYLES[action] ?? 'bg-gray-100 text-gray-600'
  return (
    <span className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${style}`}>
      {action}
    </span>
  )
}

export default function QueuePage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const activeState = searchParams.get('state') ?? 'pending'

  const [page, setPage] = useState<Page<CalloutRequest> | null>(null)
  const [pageNum, setPageNum] = useState(0)
  const [pendingLiveUpdate, setPendingLiveUpdate] = useState(false)
  const [dialogReq, setDialogReq] = useState<CalloutRequest | null>(null)
  const [auditPage, setAuditPage] = useState<AuditPage | null>(null)

  const load = useCallback(() => {
    if (activeState === 'audit') {
      fetch(`/api/audit?page=${pageNum}&size=25`, { credentials: 'include' })
        .then(r => r.ok ? r.json() : null)
        .then(setAuditPage)
        .finally(() => setPendingLiveUpdate(false))
      return
    }
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

  const [pulling, setPulling] = useState(false)
  const [pullMsg, setPullMsg] = useState<string | null>(null)

  function switchState(s: string) {
    setSearchParams({ state: s })
  }

  async function pullFromAccess() {
    setPulling(true)
    setPullMsg(null)
    try {
      const res = await fetch('/api/approvals/pull', {
        method: 'POST',
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': getCsrfToken() },
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) {
        setPullMsg(data.error || 'Could not reach Omnissa Access.')
      } else {
        setPullMsg(
          data.pulled > 0
            ? `Pulled ${data.pulled} new request${data.pulled === 1 ? '' : 's'} from Omnissa Access.`
            : `No new requests — the queue is in sync (${data.total} pending in Access).`,
        )
        load()
      }
    } catch {
      setPullMsg('Could not reach Omnissa Access.')
    } finally {
      setPulling(false)
    }
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-5">
        <h1 className="text-2xl font-semibold text-gray-900">Queue</h1>
      </div>

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
      <div className="flex gap-1 mb-5 border-b border-gray-200 overflow-x-auto md:overflow-visible pb-px md:pb-0">
        {STATE_TABS.map(tab => (
          <button
            key={tab.key}
            onClick={() => switchState(tab.key)}
            className={`shrink-0 whitespace-nowrap px-4 py-2 text-sm font-medium rounded-t-lg transition-colors -mb-px border-b-2
              ${activeState === tab.key
                ? 'border-omnissa text-omnissa'
                : 'border-transparent text-gray-500 hover:text-gray-700'}`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Audit trail */}
      {activeState === 'audit' && (
        <>
        <div className="flex justify-end mb-3">
          <a
            href="/api/approvals/export.csv"
            download
            className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 transition-colors"
          >
            Export CSV
          </a>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
          {!auditPage || auditPage.content.length === 0 ? (
            <p className="text-sm text-gray-400 px-5 py-8 text-center">No audit events recorded yet.</p>
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[640px] text-sm">
                  <thead>
                    <tr className="border-b border-gray-100 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
                      <th className="px-5 py-3">Time</th>
                      <th className="px-5 py-3">Admin</th>
                      <th className="px-5 py-3">Action</th>
                      <th className="px-5 py-3">Application</th>
                      <th className="px-5 py-3">Message</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {auditPage.content.map(ev => (
                      <tr key={ev.id} className="hover:bg-gray-50 transition-colors">
                        <td className="px-5 py-3 whitespace-nowrap text-gray-500">{formatDate(ev.timestamp)}</td>
                        <td className="px-5 py-3 text-gray-900">{ev.adminUsername}</td>
                        <td className="px-5 py-3"><AuditActionBadge action={ev.action} /></td>
                        <td className="px-5 py-3 text-gray-900">{ev.resourceName}</td>
                        <td className="px-5 py-3 text-gray-500 break-words">{ev.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Audit pagination */}
              {auditPage.totalPages > 1 && (
                <div className="flex items-center justify-between px-5 py-3 border-t border-gray-100 text-sm text-gray-500">
                  <span>Page {auditPage.number + 1} of {auditPage.totalPages}</span>
                  <div className="flex gap-2">
                    <button
                      disabled={auditPage.first}
                      onClick={() => setPageNum(p => p - 1)}
                      className="px-3 py-1 rounded border border-gray-200 disabled:opacity-40 hover:bg-gray-50"
                    >
                      ← Prev
                    </button>
                    <button
                      disabled={auditPage.last}
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
        </>
      )}

      {/* Manual pull (Awaiting Review only) */}
      {activeState === 'pending' && (
        <div className="flex flex-wrap items-center justify-end gap-3 mb-3">
          {pullMsg && <span className="text-sm text-gray-500">{pullMsg}</span>}
          <button
            onClick={pullFromAccess}
            disabled={pulling}
            className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50 transition-colors"
            title="Fetch any pending requests Omnissa Access is holding but did not push"
          >
            {pulling ? 'Pulling…' : 'Pull from Access'}
          </button>
        </div>
      )}

      {/* Request list */}
      {activeState !== 'audit' && (
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        {!page || page.content.length === 0 ? (
          <p className="text-sm text-gray-400 px-5 py-8 text-center">No requests in this category.</p>
        ) : (
          <>
            <ul className="divide-y divide-gray-100">
              {page.content.map(req => (
                <li
                  key={req.id}
                  className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-4 hover:bg-gray-50 transition-colors"
                >
                  <AppIcon resourceUuid={req.resourceUuid} resourceName={req.resourceName} size={44} />
                  <div
                    className="flex-1 min-w-[10rem] cursor-pointer"
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
      )}

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
