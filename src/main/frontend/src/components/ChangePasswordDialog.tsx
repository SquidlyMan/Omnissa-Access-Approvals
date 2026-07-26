import { useState } from 'react'
import {
  accountRequest,
  readAccountError,
  MIN_PASSWORD_LENGTH,
  PASSWORD_GUIDANCE,
  PASSWORD_RULE,
} from '../lib/accounts'
import type { AccountError } from '../lib/accounts'
import type { UserSummary } from '../types'
import AccountErrorNotice from './AccountErrorNotice'

interface Props {
  /**
   * The account whose password an admin is resetting. Omit it for the
   * signed-in user changing their own password, which additionally requires
   * the current password.
   */
  account?: UserSummary
  onClose: () => void
  onDone: () => void
}

/**
 * Sets a local account's password — either your own (PUT /me/password, needs
 * the current one) or someone else's as an admin (PUT /{id}/password). The
 * length rule is checked here as a courtesy; the server enforces it.
 */
export default function ChangePasswordDialog({ account, onClose, onDone }: Props) {
  const reset = account !== undefined
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<AccountError | null>(null)

  const tooShort = newPassword.length > 0 && newPassword.length < MIN_PASSWORD_LENGTH
  const mismatch = confirmPassword.length > 0 && confirmPassword !== newPassword
  const ready = newPassword.length >= MIN_PASSWORD_LENGTH
    && confirmPassword === newPassword
    && (reset || currentPassword.length > 0)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!ready) return
    setSubmitting(true)
    setError(null)
    try {
      const res = reset
        ? await accountRequest(`/api/users/${account.id}/password`, 'PUT', { newPassword })
        : await accountRequest('/api/users/me/password', 'PUT', { currentPassword, newPassword })
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
        <h2 className="text-lg font-semibold text-gray-900 mb-1">
          {reset ? 'Reset password' : 'Change my password'}
        </h2>
        <p className="text-sm text-gray-500 mb-4">
          {reset ? (
            <>Account: <span className="font-medium text-gray-700">{account.username}</span></>
          ) : (
            'Your local sign-in password for this tool.'
          )}
        </p>

        {reset && (
          <div className="mb-4 rounded-lg bg-amber-50 border border-amber-200 px-3 py-2.5 text-sm text-amber-900 space-y-1.5">
            <p>
              The existing password stops working immediately. Tell{' '}
              <span className="font-medium">{account.username}</span> the new one over a
              channel you trust — this tool never emails it.
            </p>
            <p className="text-xs text-amber-700">The reset is recorded in the audit trail.</p>
          </div>
        )}

        <form onSubmit={submit} className="space-y-4">
          {!reset && (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Current password</label>
              <input
                type="password"
                value={currentPassword}
                onChange={e => setCurrentPassword(e.target.value)}
                autoComplete="current-password"
                autoFocus
                required
                className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
              />
            </div>
          )}

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">New password</label>
            <input
              type="password"
              value={newPassword}
              onChange={e => setNewPassword(e.target.value)}
              autoComplete="new-password"
              autoFocus={reset}
              required
              className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
            />
            <p className={`text-xs mt-1 ${tooShort ? 'text-red-600' : 'text-gray-400'}`}>{PASSWORD_RULE}</p>
            <p className="text-xs text-gray-400 mt-0.5">{PASSWORD_GUIDANCE}</p>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Confirm new password</label>
            <input
              type="password"
              value={confirmPassword}
              onChange={e => setConfirmPassword(e.target.value)}
              autoComplete="new-password"
              required
              className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
            />
            {mismatch && <p className="text-xs text-red-600 mt-1">The two passwords do not match.</p>}
          </div>

          <AccountErrorNotice error={error} />

          <div className="flex justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="px-4 py-2 text-sm rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={!ready || submitting}
              className="px-4 py-2 text-sm rounded-lg bg-omnissa text-white font-medium hover:bg-omnissa-dark disabled:opacity-50 transition-colors"
            >
              {submitting ? 'Saving…' : (reset ? 'Reset password' : 'Change password')}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
