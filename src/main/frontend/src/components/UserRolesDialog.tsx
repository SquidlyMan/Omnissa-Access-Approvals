import { useState } from 'react'
import { accountRequest, readAccountError, rolesOfAccount, ROLE_OPTIONS } from '../lib/accounts'
import type { AccountError } from '../lib/accounts'
import type { UserSummary } from '../types'
import AccountErrorNotice from './AccountErrorNotice'

interface Props {
  account: UserSummary
  onClose: () => void
  onDone: () => void
}

/**
 * Replaces an account's roles. The endpoint takes the full set rather than
 * add/remove, so the selection here is the result — and the server refuses
 * with 409 when clearing Admin would leave no enabled local admin.
 */
export default function UserRolesDialog({ account, onClose, onDone }: Props) {
  const [selected, setSelected] = useState<Set<string>>(() => rolesOfAccount(account.roles))
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<AccountError | null>(null)

  function toggle(role: string) {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(role)) next.delete(role)
      else next.add(role)
      return next
    })
  }

  async function submit() {
    if (selected.size === 0) return
    setSubmitting(true)
    setError(null)
    try {
      const res = await accountRequest(`/api/users/${account.id}/roles`, 'PUT', {
        roles: ROLE_OPTIONS.filter(o => selected.has(o.role)).map(o => o.role),
      })
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
        <h2 className="text-lg font-semibold text-gray-900 mb-1">Edit roles</h2>
        <p className="text-sm text-gray-500 mb-4">
          Account: <span className="font-medium text-gray-700">{account.username}</span>
        </p>

        <div className="space-y-2 mb-4">
          {ROLE_OPTIONS.map(option => (
            <label
              key={option.role}
              className="flex items-start gap-2.5 rounded-lg border border-gray-200 px-3 py-2.5 text-sm text-gray-700 cursor-pointer hover:bg-gray-50 transition-colors"
            >
              <input
                type="checkbox"
                checked={selected.has(option.role)}
                onChange={() => toggle(option.role)}
                className="mt-0.5 accent-omnissa"
              />
              <span>
                <span className="font-medium text-gray-800">{option.label}</span>
                <span className="block text-xs text-gray-500">{option.description}</span>
              </span>
            </label>
          ))}
        </div>

        <p className="text-xs text-gray-400 mb-4">
          Roles are additive — the account gets everything its roles allow.
        </p>

        {selected.size === 0 && (
          <p className="text-red-600 text-sm mb-3">An account must hold at least one role.</p>
        )}

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
            disabled={submitting || selected.size === 0}
            className="px-4 py-2 text-sm rounded-lg bg-omnissa text-white font-medium hover:bg-omnissa-dark disabled:opacity-50 transition-colors"
          >
            {submitting ? 'Saving…' : 'Save roles'}
          </button>
        </div>
      </div>
    </div>
  )
}
