import { useState } from 'react'

interface Props {
  requestId: string
  resourceName: string
  onClose: () => void
  onComplete: () => void
}

export default function ApprovalDialog({ requestId, resourceName, onClose, onComplete }: Props) {
  const [approved, setApproved] = useState<boolean | null>(null)
  const [message, setMessage] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  async function submit() {
    if (approved === null) return
    setSubmitting(true)
    setError('')
    try {
      const res = await fetch('/api/approvals/response', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requestId, approved, responseMessage: message }),
      })
      if (!res.ok) throw new Error(`Server error ${res.status}`)
      onComplete()
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Request failed')
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4 p-6">
        <h2 className="text-lg font-semibold text-gray-900 mb-1">Review Request</h2>
        <p className="text-sm text-gray-500 mb-5">
          Application: <span className="font-medium text-gray-700">{resourceName}</span>
        </p>

        <div className="flex gap-3 mb-5">
          <button
            onClick={() => setApproved(true)}
            className={`flex-1 rounded-lg border-2 py-2.5 text-sm font-medium transition-colors
              ${approved === true
                ? 'border-green-500 bg-green-50 text-green-700'
                : 'border-gray-200 text-gray-600 hover:border-green-300'}`}
          >
            ✓ Approve
          </button>
          <button
            onClick={() => setApproved(false)}
            className={`flex-1 rounded-lg border-2 py-2.5 text-sm font-medium transition-colors
              ${approved === false
                ? 'border-red-500 bg-red-50 text-red-700'
                : 'border-gray-200 text-gray-600 hover:border-red-300'}`}
          >
            ✗ Reject
          </button>
        </div>

        <textarea
          value={message}
          onChange={e => setMessage(e.target.value)}
          placeholder="Optional message to the requestor…"
          rows={3}
          className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa resize-none mb-4"
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
            onClick={submit}
            disabled={approved === null || submitting}
            className="px-4 py-2 text-sm rounded-lg bg-omnissa text-white font-medium hover:bg-omnissa-dark disabled:opacity-50 transition-colors"
          >
            {submitting ? 'Submitting…' : 'Submit'}
          </button>
        </div>
      </div>
    </div>
  )
}
