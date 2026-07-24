import { useState } from 'react'
import { getCsrfToken } from '../utils/csrf'

interface Props {
  requestId: string
  resourceName: string
  onClose: () => void
  onComplete: () => void
}

// Time-bound (JIT) access options. null = permanent (today's default).
const TTL_OPTIONS: { label: string; minutes: number | null }[] = [
  { label: 'Permanent', minutes: null },
  { label: '5 minutes', minutes: 5 },
  { label: '15 minutes', minutes: 15 },
  { label: '1 hour', minutes: 60 },
  { label: '8 hours', minutes: 480 },
  { label: '24 hours', minutes: 1440 },
  { label: '7 days', minutes: 10080 },
  { label: '30 days', minutes: 43200 },
]

export default function ApprovalDialog({ requestId, resourceName, onClose, onComplete }: Props) {
  const [approved, setApproved] = useState<boolean | null>(null)
  const [message, setMessage] = useState('')
  const [ttlMinutes, setTtlMinutes] = useState<number | null>(null)
  const [reRequestable, setReRequestable] = useState(true) // Option 2 default
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [expired, setExpired] = useState(false)

  async function submit() {
    if (approved === null) return
    setSubmitting(true)
    setError('')
    try {
      const res = await fetch('/api/approvals/response', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
        // ttlMinutes/reRequestable only apply to timed approvals.
        body: JSON.stringify({
          requestId, approved, message,
          ttlMinutes: approved ? ttlMinutes : null,
          reRequestable: approved && ttlMinutes != null ? reRequestable : null,
        }),
      })
      if (!res.ok) throw new Error(`Server error ${res.status}`)
      const data: { outcome?: string } | null = await res.json().catch(() => null)
      const outcome = data?.outcome ?? 'delivered'
      if (outcome === 'expired') {
        setExpired(true)
        setSubmitting(false)
      } else if (outcome === 'unreachable') {
        setError('Could not reach Omnissa Access — decision not delivered. Try again.')
        setSubmitting(false)
      } else {
        onComplete()
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Request failed')
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-md p-6 max-h-full overflow-y-auto">
        <h2 className="text-lg font-semibold text-gray-900 mb-1">Review Request</h2>
        <p className="text-sm text-gray-500 mb-5">
          Application: <span className="font-medium text-gray-700">{resourceName}</span>
        </p>

        <div className="flex gap-3 mb-5">
          <button
            onClick={() => setApproved(true)}
            disabled={expired}
            className={`flex-1 rounded-lg border-2 py-2.5 text-sm font-medium transition-colors disabled:opacity-50
              ${approved === true
                ? 'border-green-500 bg-green-50 text-green-700'
                : 'border-gray-200 text-gray-600 hover:border-green-300'}`}
          >
            ✓ Approve
          </button>
          <button
            onClick={() => setApproved(false)}
            disabled={expired}
            className={`flex-1 rounded-lg border-2 py-2.5 text-sm font-medium transition-colors disabled:opacity-50
              ${approved === false
                ? 'border-red-500 bg-red-50 text-red-700'
                : 'border-gray-200 text-gray-600 hover:border-red-300'}`}
          >
            ✗ Reject
          </button>
        </div>

        {approved === true && !expired && (
          <div className="mb-5">
            <label className="block text-sm font-medium text-gray-700 mb-1">Access duration</label>
            <select
              value={ttlMinutes ?? ''}
              onChange={e => setTtlMinutes(e.target.value === '' ? null : Number(e.target.value))}
              className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
            >
              {TTL_OPTIONS.map(o => (
                <option key={o.label} value={o.minutes ?? ''}>{o.label}</option>
              ))}
            </select>
            <p className="mt-1 text-xs text-gray-400">
              {ttlMinutes == null
                ? 'Access stays until manually removed.'
                : 'Access is automatically revoked in Omnissa Access when the time expires.'}
            </p>

            {ttlMinutes != null && (
              <label className="mt-3 flex items-start gap-2 text-sm text-gray-700 cursor-pointer">
                <input
                  type="checkbox"
                  checked={reRequestable}
                  onChange={e => setReRequestable(e.target.checked)}
                  className="mt-0.5 accent-omnissa"
                />
                <span>
                  Allow the user to re-request after expiration
                  <span
                    className="ml-1 text-gray-400 cursor-help"
                    title={
                      'On: when the time expires the user is excluded (access removed and the app '
                      + 'deprovisioned), then after a short hold the exclusion is lifted so the app '
                      + 'returns to a requestable state.\n\n'
                      + 'Off: one-time access — after expiry the user stays excluded and the app '
                      + 'does not reappear (permanent revoke).'
                    }
                  >
                    ⓘ
                  </span>
                  <span className="block text-xs text-gray-400 mt-0.5">
                    {reRequestable
                      ? 'One-time now; app becomes requestable again shortly after expiry.'
                      : 'One-time only; app is permanently removed after expiry.'}
                  </span>
                </span>
              </label>
            )}
          </div>
        )}

        <textarea
          value={message}
          onChange={e => setMessage(e.target.value)}
          disabled={expired}
          placeholder="Optional message to the requestor…"
          rows={3}
          className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa resize-none mb-4 disabled:opacity-50"
        />

        {expired && (
          <div className="mb-3 rounded-lg bg-amber-50 border border-amber-200 px-3 py-2 text-sm text-amber-800">
            Request no longer exists in Omnissa Access — moved to Deactivated.
          </div>
        )}
        {error && <p className="text-red-600 text-sm mb-3">{error}</p>}

        <div className="flex justify-end gap-3">
          {expired ? (
            <button
              onClick={onComplete}
              className="px-4 py-2 text-sm rounded-lg bg-omnissa text-white font-medium hover:bg-omnissa-dark transition-colors"
            >
              Close
            </button>
          ) : (
            <>
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
            </>
          )}
        </div>
      </div>
    </div>
  )
}
