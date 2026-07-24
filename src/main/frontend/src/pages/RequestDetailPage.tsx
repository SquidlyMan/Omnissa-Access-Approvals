import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import AppIcon from '../components/AppIcon'
import StatusBadge from '../components/StatusBadge'
import ApprovalDialog from '../components/ApprovalDialog'
import DeleteRequestDialog from '../components/DeleteRequestDialog'
import type { CalloutRequest } from '../types'
import { requesterLabel } from '../utils/requester'

export default function RequestDetailPage() {
  const { requestId } = useParams<{ requestId: string }>()
  const navigate = useNavigate()
  const [req, setReq] = useState<CalloutRequest | null>(null)
  const [loading, setLoading] = useState(true)
  const [showDialog, setShowDialog] = useState(false)
  const [showDelete, setShowDelete] = useState(false)

  useEffect(() => {
    fetch(`/api/approvals/requests/${requestId}`, { credentials: 'include' })
      .then(r => r.ok ? r.json() : null)
      .then(setReq)
      .finally(() => setLoading(false))
  }, [requestId])

  if (loading) return <p className="text-sm text-gray-400 py-10 text-center">Loading…</p>
  if (!req) return <p className="text-sm text-red-500 py-10 text-center">Request not found.</p>

  const requestor = requesterLabel(req)
  const email     = req.userAttributes?.['email']?.[0]     ?? req.userId

  return (
    <div className="max-w-2xl">
      {/* Back */}
      <button
        onClick={() => navigate(-1)}
        className="text-sm text-omnissa hover:underline mb-4 flex items-center gap-1"
      >
        ← Back
      </button>

      {/* App header */}
      <div className="bg-white rounded-xl border border-gray-200 p-5 mb-4 flex flex-wrap items-center gap-x-4 gap-y-2">
        <AppIcon resourceUuid={req.resourceUuid} resourceName={req.resourceName} size={56} />
        <div className="flex-1 min-w-[10rem]">
          <h1 className="text-xl font-semibold text-gray-900 truncate">{req.resourceName}</h1>
          <p className="text-sm text-gray-500 mt-0.5 break-all">Request ID: {req.requestId}</p>
        </div>
        <StatusBadge state={req.state} />
      </div>

      {/* Details grid */}
      <div className="bg-white rounded-xl border border-gray-200 divide-y divide-gray-100 mb-4">
        <Row label="Requestor">{requestor}</Row>
        <Row label="Email">{email}</Row>
        <Row label="Device">{req.userDeviceName || '—'}</Row>
        <Row label="Operation">{req.operation}</Row>
        <Row label="Received">{formatDate(req.receivedDate)}</Row>
        {req.responseDate && <Row label="Decided">{formatDate(req.responseDate)}</Row>}
        {req.accessTtlMinutes != null && (
          <Row label="Access duration">{formatDuration(req.accessTtlMinutes)}</Row>
        )}
        {req.accessExpiresAt && req.state !== 'revoked' && (
          <Row label="Access expires">{formatDate(req.accessExpiresAt)}</Row>
        )}
        {req.revokedAt && <Row label="Access revoked">{formatDate(req.revokedAt)}</Row>}
        {req.responseMessage && <Row label="Message">{req.responseMessage}</Row>}
        {req.notes && <Row label="Notes">{req.notes}</Row>}
      </div>

      {/* User attributes */}
      {Object.keys(req.userAttributes ?? {}).length > 0 && (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden mb-4">
          <h2 className="px-5 py-3 text-sm font-semibold text-gray-700 border-b border-gray-100">User Attributes</h2>
          <div className="divide-y divide-gray-100">
            {Object.entries(req.userAttributes).map(([key, vals]) => (
              <Row key={key} label={key}>{vals.join(', ')}</Row>
            ))}
          </div>
        </div>
      )}

      {/* Action */}
      {req.state === 'pending' && (
        <button
          onClick={() => setShowDialog(true)}
          className="w-full py-2.5 rounded-xl bg-omnissa text-white font-medium hover:bg-omnissa-dark transition-colors"
        >
          Review this request
        </button>
      )}

      {/* Destructive: remove a stale/orphaned local record. Does not touch Access. */}
      <div className="mt-6 pt-4 border-t border-gray-100 flex items-center justify-between gap-3">
        <span className="text-xs text-gray-400">
          Remove this request from the tool. Local only — does not affect Omnissa Access.
        </span>
        <button
          onClick={() => setShowDelete(true)}
          className="shrink-0 text-sm px-3 py-1.5 rounded-lg border border-red-200 text-red-600 hover:bg-red-50 transition-colors"
        >
          Delete request
        </button>
      </div>

      {showDialog && (
        <ApprovalDialog
          requestId={req.requestId}
          resourceName={req.resourceName}
          onClose={() => setShowDialog(false)}
          onComplete={() => {
            setShowDialog(false)
            navigate('/queue?state=pending')
          }}
        />
      )}

      {showDelete && (
        <DeleteRequestDialog
          requestId={req.requestId}
          resourceName={req.resourceName}
          onClose={() => setShowDelete(false)}
          onDeleted={() => {
            setShowDelete(false)
            navigate('/queue?state=pending')
          }}
        />
      )}
    </div>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col sm:flex-row px-5 py-3 text-sm gap-1 sm:gap-4">
      <span className="sm:w-32 shrink-0 text-gray-500">{label}</span>
      <span className="text-gray-900 break-all">{children}</span>
    </div>
  )
}

function formatDate(iso: string) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString()
}

/** Humanize a TTL in minutes: 60 → "1 hour", 1440 → "1 day", etc. */
function formatDuration(minutes: number): string {
  if (minutes % 1440 === 0) {
    const d = minutes / 1440
    return `${d} day${d === 1 ? '' : 's'}`
  }
  if (minutes % 60 === 0) {
    const h = minutes / 60
    return `${h} hour${h === 1 ? '' : 's'}`
  }
  return `${minutes} min`
}
