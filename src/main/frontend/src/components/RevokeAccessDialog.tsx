import { useState } from 'react'
import { getCsrfToken } from '../utils/csrf'
import { FORBIDDEN_MESSAGE } from '../lib/permissions'

interface Props {
  requestId: string
  resourceName: string
  permanent: boolean
  onClose: () => void
  onDone: () => void
}

/**
 * Confirmation for revoking an already-granted app. Both modes exclude the user
 * in Omnissa Access (which deprovisions the running app); the permanent mode
 * leaves that exclusion in place. Spells out the Access-side and rule-side
 * behavior that can re-grant access, so the admin isn't surprised.
 */
export default function RevokeAccessDialog({ requestId, resourceName, permanent, onClose, onDone }: Props) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  async function submit() {
    setSubmitting(true)
    setError('')
    try {
      const res = await fetch(
        `/api/approvals/requests/${requestId}/revoke?permanent=${permanent}`,
        { method: 'POST', credentials: 'include', headers: { 'X-XSRF-TOKEN': getCsrfToken() } },
      )
      const data: { outcome?: string; error?: string } = await res.json().catch(() => ({}))
      if (res.status === 403) throw new Error(FORBIDDEN_MESSAGE)
      if (!res.ok) throw new Error(data.error || `Server error ${res.status}`)
      if (data.outcome === 'revoked' || data.outcome === 'already_absent') {
        onDone()
      } else if (data.outcome === 'unreachable') {
        setError('Could not reach Omnissa Access — nothing was changed. Try again.')
        setSubmitting(false)
      } else {
        setError('Could not apply the exclusion — nothing was changed. Check the logs.')
        setSubmitting(false)
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Request failed')
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-md p-6 max-h-full overflow-y-auto">
        <h2 className={`text-lg font-semibold mb-1 ${permanent ? 'text-red-700' : 'text-gray-900'}`}>
          {permanent ? 'Revoke access and block' : 'Revoke access'}
        </h2>
        <p className="text-sm text-gray-500 mb-4">
          Application: <span className="font-medium text-gray-700">{resourceName}</span>
        </p>

        <div className={`mb-4 rounded-lg border px-3 py-2.5 text-sm space-y-2
          ${permanent ? 'bg-red-50 border-red-200 text-red-800' : 'bg-amber-50 border-amber-200 text-amber-900'}`}>
          <p>
            The user is excluded from this app in Omnissa Access, which removes the app
            they currently have.
          </p>
          {permanent ? (
            <p>
              The exclusion <span className="font-semibold">stays in place</span> — the app will not
              reappear for them. You can undo this later from this request in the{' '}
              <span className="font-medium">Deactivated</span> section using{' '}
              <span className="font-medium">Allow re-request</span>.
            </p>
          ) : (
            <p>
              After about a minute the exclusion is lifted and the app returns to a
              requestable state.
            </p>
          )}
        </div>

        {!permanent && (
          <div className="mb-4 rounded-lg bg-gray-50 border border-gray-200 px-3 py-2.5 text-xs text-gray-600 space-y-1.5">
            <p className="font-medium text-gray-700">Note — access may be re-granted automatically</p>
            <p>
              If the group assignment's <span className="font-medium">Deployment Type</span> is{' '}
              <span className="font-medium">Automatic</span>, Omnissa Access re-provisions the app as
              soon as the exclusion is lifted — the user never has to request it.
            </p>
            <p>
              If an <span className="font-medium">Auto-Approval Rule</span> matches, a new request is
              approved immediately.
            </p>
          </div>
        )}

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
            disabled={submitting}
            className={`px-4 py-2 text-sm rounded-lg text-white font-medium disabled:opacity-50 transition-colors
              ${permanent ? 'bg-red-600 hover:bg-red-700' : 'bg-omnissa hover:bg-omnissa-dark'}`}
          >
            {submitting ? 'Revoking…' : (permanent ? 'Revoke and block' : 'Revoke access')}
          </button>
        </div>
      </div>
    </div>
  )
}
