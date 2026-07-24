import { useState } from 'react'
import { getCsrfToken } from '../utils/csrf'

interface Props {
  requestId: string
  resourceName: string
  onClose: () => void
  onDeleted: () => void
}

/**
 * Two-step, type-to-confirm deletion of a request's LOCAL tool record. This is
 * a destructive admin cleanup for stale/orphaned entries; it never contacts
 * Omnissa Access. The backend fully audits every deletion.
 */
export default function DeleteRequestDialog({ requestId, resourceName, onClose, onDeleted }: Props) {
  const [step, setStep] = useState<1 | 2>(1)
  const [acknowledged, setAcknowledged] = useState(false)
  const [confirmText, setConfirmText] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  async function doDelete() {
    if (confirmText !== 'DELETE') return
    setSubmitting(true)
    setError('')
    try {
      const res = await fetch(`/api/approvals/requests/${requestId}`, {
        method: 'DELETE',
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': getCsrfToken() },
      })
      if (!res.ok) throw new Error(`Server error ${res.status}`)
      onDeleted()
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Request failed')
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-md p-6 max-h-full overflow-y-auto">
        <h2 className="text-lg font-semibold text-red-700 mb-1">Delete Request Record</h2>
        <p className="text-sm text-gray-500 mb-4">
          Application: <span className="font-medium text-gray-700">{resourceName}</span>
        </p>

        <div className="mb-4 rounded-lg bg-red-50 border border-red-200 px-3 py-2.5 text-sm text-red-800 space-y-1.5">
          <p className="font-medium">This permanently deletes the local record from this tool.</p>
          <p>It does <span className="font-semibold">not</span> contact Omnissa Access or change any
             entitlement, approval, or access. It cannot be undone.</p>
          <p className="text-xs text-red-600 break-all">Request ID: {requestId}</p>
        </div>

        {step === 1 ? (
          <>
            <label className="flex items-start gap-2 text-sm text-gray-700 mb-4 cursor-pointer">
              <input
                type="checkbox"
                checked={acknowledged}
                onChange={e => setAcknowledged(e.target.checked)}
                className="mt-0.5 accent-red-600"
              />
              I understand this permanently removes the record and cannot be undone.
            </label>
            <div className="flex justify-end gap-3">
              <button
                onClick={onClose}
                className="px-4 py-2 text-sm rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={() => setStep(2)}
                disabled={!acknowledged}
                className="px-4 py-2 text-sm rounded-lg bg-red-600 text-white font-medium hover:bg-red-700 disabled:opacity-50 transition-colors"
              >
                Continue
              </button>
            </div>
          </>
        ) : (
          <>
            <label className="block text-sm text-gray-700 mb-1">
              Type <span className="font-mono font-semibold">DELETE</span> to confirm:
            </label>
            <input
              type="text"
              value={confirmText}
              onChange={e => setConfirmText(e.target.value)}
              autoFocus
              placeholder="DELETE"
              className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-red-500 mb-4"
            />
            {error && <p className="text-red-600 text-sm mb-3">{error}</p>}
            <div className="flex justify-end gap-3">
              <button
                onClick={onClose}
                disabled={submitting}
                className="px-4 py-2 text-sm rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={doDelete}
                disabled={confirmText !== 'DELETE' || submitting}
                className="px-4 py-2 text-sm rounded-lg bg-red-600 text-white font-medium hover:bg-red-700 disabled:opacity-50 transition-colors"
              >
                {submitting ? 'Deleting…' : 'Permanently Delete'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
