import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import AppIcon from '../components/AppIcon'
import StatusBadge from '../components/StatusBadge'
import ApprovalDialog from '../components/ApprovalDialog'
import type { CalloutRequest } from '../types'

export default function RequestDetailPage() {
  const { requestId } = useParams<{ requestId: string }>()
  const navigate = useNavigate()
  const [req, setReq] = useState<CalloutRequest | null>(null)
  const [loading, setLoading] = useState(true)
  const [showDialog, setShowDialog] = useState(false)

  useEffect(() => {
    fetch(`/api/approvals/requests/${requestId}`, { credentials: 'include' })
      .then(r => r.ok ? r.json() : null)
      .then(setReq)
      .finally(() => setLoading(false))
  }, [requestId])

  if (loading) return <p className="text-sm text-gray-400 py-10 text-center">Loading…</p>
  if (!req) return <p className="text-sm text-red-500 py-10 text-center">Request not found.</p>

  const firstName = req.userAttributes?.['firstName']?.[0] ?? ''
  const lastName  = req.userAttributes?.['lastName']?.[0]  ?? ''
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
      <div className="bg-white rounded-xl border border-gray-200 p-5 mb-4 flex items-center gap-4">
        <AppIcon resourceUuid={req.resourceUuid} resourceName={req.resourceName} size={56} />
        <div className="flex-1 min-w-0">
          <h1 className="text-xl font-semibold text-gray-900 truncate">{req.resourceName}</h1>
          <p className="text-sm text-gray-500 mt-0.5">Request ID: {req.requestId}</p>
        </div>
        <StatusBadge state={req.state} />
      </div>

      {/* Details grid */}
      <div className="bg-white rounded-xl border border-gray-200 divide-y divide-gray-100 mb-4">
        <Row label="Requestor">{firstName || lastName ? `${firstName} ${lastName}`.trim() : req.userId}</Row>
        <Row label="Email">{email}</Row>
        <Row label="Device">{req.userDeviceName || '—'}</Row>
        <Row label="Operation">{req.operation}</Row>
        <Row label="Received">{formatDate(req.receivedDate)}</Row>
        {req.responseDate && <Row label="Decided">{formatDate(req.responseDate)}</Row>}
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
    </div>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex px-5 py-3 text-sm gap-4">
      <span className="w-32 shrink-0 text-gray-500">{label}</span>
      <span className="text-gray-900 break-all">{children}</span>
    </div>
  )
}

function formatDate(iso: string) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString()
}
