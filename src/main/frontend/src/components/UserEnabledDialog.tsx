import { useState } from 'react'
import { accountRequest, readAccountError } from '../lib/accounts'
import type { AccountError } from '../lib/accounts'
import type { UserSummary } from '../types'
import AccountErrorNotice from './AccountErrorNotice'

interface Props {
  account: UserSummary
  /** What the account should become. Disabling is the destructive direction. */
  enable: boolean
  onClose: () => void
  onDone: () => void
}

/**
 * Confirms enabling or disabling a local account. Disabling keeps the record
 * and its history but blocks sign-in; the server refuses it with 409 when the
 * account is the last enabled local admin.
 */
export default function UserEnabledDialog({ account, enable, onClose, onDone }: Props) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<AccountError | null>(null)

  async function submit() {
    setSubmitting(true)
    setError(null)
    try {
      const res = await accountRequest(`/api/users/${account.id}/enabled`, 'PUT', { enabled: enable })
      if (!res.ok) {
        setError(await readAccountError(res))
        setSubmitting(false)
        return
      }
      onDone()
    } catch {
      setError({ message: 'Request failed — check your connection and try again.', refused: false })
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-md p-6 max-h-full overflow-y-auto">
        <h2 className={`text-lg font-semibold mb-1 ${enable ? 'text-gray-900' : 'text-red-700'}`}>
          {enable ? 'Enable account' : 'Disable account'}
        </h2>
        <p className="text-sm text-gray-500 mb-4">
          Account: <span className="font-medium text-gray-700">{account.username}</span>
        </p>

        <div className={`mb-4 rounded-lg border px-3 py-2.5 text-sm space-y-1.5
          ${enable ? 'bg-gray-50 border-gray-200 text-gray-600' : 'bg-red-50 border-red-200 text-red-800'}`}>
          {enable ? (
            <p>
              The account can sign in again with its existing password and the roles it
              already holds.
            </p>
          ) : (
            <>
              <p>
                The account can no longer sign in. Nothing is deleted — its roles and history
                stay, and you can enable it again here.
              </p>
              <p className="text-xs text-red-600">
                Any session it already has ends at the next sign-in check.
              </p>
            </>
          )}
        </div>

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
            onClick={submit}
            disabled={submitting}
            className={`px-4 py-2 text-sm rounded-lg text-white font-medium disabled:opacity-50 transition-colors
              ${enable ? 'bg-omnissa hover:bg-omnissa-dark' : 'bg-red-600 hover:bg-red-700'}`}
          >
            {submitting ? 'Saving…' : (enable ? 'Enable account' : 'Disable account')}
          </button>
        </div>
      </div>
    </div>
  )
}
