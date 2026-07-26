import { useState } from 'react'
import { accountRequest, readAccountError } from '../lib/accounts'
import type { AccountError } from '../lib/accounts'
import type { UserSummary } from '../types'
import AccountErrorNotice from './AccountErrorNotice'

interface Props {
  account: UserSummary
  onClose: () => void
  onDeleted: () => void
}

/**
 * Two-step, type-to-confirm deletion of a local account, matching the request
 * deletion dialog. Deleting is permanent and, unlike disabling, leaves nothing
 * to re-enable — the server refuses it with 409 when this is the last enabled
 * local admin. Every deletion is audited.
 */
export default function DeleteUserDialog({ account, onClose, onDeleted }: Props) {
  const [step, setStep] = useState<1 | 2>(1)
  const [acknowledged, setAcknowledged] = useState(false)
  const [confirmText, setConfirmText] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<AccountError | null>(null)

  async function doDelete() {
    if (confirmText !== 'DELETE') return
    setSubmitting(true)
    setError(null)
    try {
      const res = await accountRequest(`/api/users/${account.id}`, 'DELETE')
      if (!res.ok) {
        setError(await readAccountError(res))
        setSubmitting(false)
        return
      }
      onDeleted()
    } catch {
      setError({ message: 'Request failed — check your connection and try again.', refused: false })
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-md p-6 max-h-full overflow-y-auto">
        <h2 className="text-lg font-semibold text-red-700 mb-1">Delete Local Account</h2>
        <p className="text-sm text-gray-500 mb-4">
          Account: <span className="font-medium text-gray-700">{account.username}</span>
        </p>

        <div className="mb-4 rounded-lg bg-red-50 border border-red-200 px-3 py-2.5 text-sm text-red-800 space-y-1.5">
          <p className="font-medium">This permanently removes the sign-in account from this tool.</p>
          <p>
            It does <span className="font-semibold">not</span> contact Omnissa Access or change any
            entitlement. If you only want to block sign-in, disable the account instead — that can
            be undone.
          </p>
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
              I understand this permanently removes the account and cannot be undone.
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
            <AccountErrorNotice
              error={error}
              hint="At least one enabled local admin must remain — local sign-in is the way back in when Omnissa Access is unreachable."
            />
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
