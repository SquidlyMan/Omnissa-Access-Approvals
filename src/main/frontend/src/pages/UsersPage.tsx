import { useState, useEffect, useCallback } from 'react'
import { useAuth } from '../hooks/useAuth'
import { FORBIDDEN_MESSAGE } from '../lib/permissions'
import {
  accountRequest,
  readAccountError,
  roleLabelOf,
  MIN_PASSWORD_LENGTH,
  PASSWORD_GUIDANCE,
  PASSWORD_RULE,
} from '../lib/accounts'
import type { AccountError } from '../lib/accounts'
import type { UserSummary } from '../types'
import AccountErrorNotice from '../components/AccountErrorNotice'
import ChangePasswordDialog from '../components/ChangePasswordDialog'
import UserRolesDialog from '../components/UserRolesDialog'
import UserEnabledDialog from '../components/UserEnabledDialog'
import DeleteUserDialog from '../components/DeleteUserDialog'

/** Which dialog is open, and for which account. */
type Dialog =
  | { kind: 'password'; account: UserSummary }
  | { kind: 'roles'; account: UserSummary }
  | { kind: 'enabled'; account: UserSummary; enable: boolean }
  | { kind: 'delete'; account: UserSummary }

const rowButtonClass =
  'text-sm px-3 py-1.5 rounded-lg border border-gray-200 text-gray-700 hover:bg-gray-50 transition-colors'

/**
 * Local account management (#58). These are the tool's own sign-in accounts,
 * separate from Omnissa Access identities: they are the break-glass route in
 * when the tenant is unreachable or the role mapping is wrong, which is why
 * the server refuses any change that would leave no enabled local admin.
 */
export default function UsersPage() {
  const { user } = useAuth()
  const [accounts, setAccounts] = useState<UserSummary[]>([])
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [dialog, setDialog] = useState<Dialog | null>(null)

  // Add-account form state
  const [username, setUsername] = useState('')
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [formError, setFormError] = useState<AccountError | null>(null)

  const load = useCallback(() => {
    fetch('/api/users', { credentials: 'include' })
      .then(r => {
        if (r.status === 403) return Promise.reject(new Error(FORBIDDEN_MESSAGE))
        if (!r.ok) return Promise.reject(new Error(`Server error ${r.status}`))
        return r.json()
      })
      .then((data: UserSummary[]) => {
        // Stable order so a rename or role change never reshuffles the table.
        setAccounts([...data].sort((a, b) => a.username.localeCompare(b.username)))
        setError('')
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Failed to load accounts.'))
  }, [])

  useEffect(() => { load() }, [load])

  /** Applies a dialog's result: refresh the table and say what happened. */
  function finish(message: string) {
    setDialog(null)
    setNotice(message)
    load()
  }

  async function addAccount(e: React.FormEvent) {
    e.preventDefault()
    if (password.length < MIN_PASSWORD_LENGTH) {
      setFormError({ message: PASSWORD_RULE, refused: false })
      return
    }
    setSubmitting(true)
    setFormError(null)
    try {
      const res = await accountRequest('/api/users', 'POST', {
        username: username.trim(),
        password,
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        email: email.trim(),
      })
      if (!res.ok) {
        setFormError(await readAccountError(res))
        return
      }
      const created: UserSummary = await res.json()
      setUsername('')
      setFirstName('')
      setLastName('')
      setEmail('')
      setPassword('')
      setNotice(`Account "${created.username}" created with the Viewer role. Use Edit roles to grant more.`)
      load()
    } catch {
      setFormError({ message: 'Request failed — check your connection and try again.', refused: false })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold text-gray-900 mb-2">Local Accounts</h1>
      <p className="text-sm text-gray-500 mb-2 max-w-3xl">
        Accounts that sign in to this tool directly with a username and password, separate from
        Omnissa Access identities. Everything here — creation, role changes, resets, deletions —
        is recorded in the Audit trail.
      </p>
      <p className="text-xs text-gray-400 mb-6 max-w-3xl">
        Local sign-in is the way back in when Omnissa Access is unreachable or a group-to-role
        mapping is wrong, so the server refuses any change that would leave no enabled local
        admin. New accounts always start as Viewer; raising that is a separate, deliberate step.
      </p>

      {error && (
        <div className="mb-4 rounded-lg bg-red-50 border border-red-200 px-4 py-2 text-sm text-red-700">
          {error}
        </div>
      )}
      {notice && (
        <div className="mb-4 flex items-start justify-between gap-4 rounded-lg bg-green-50 border border-green-200 px-4 py-2 text-sm text-green-800">
          <span>{notice}</span>
          <button onClick={() => setNotice('')} className="text-green-700 hover:text-green-900 shrink-0">
            Dismiss
          </button>
        </div>
      )}

      {/* Account list */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden mb-6">
        {accounts.length === 0 ? (
          <p className="text-sm text-gray-400 px-5 py-8 text-center">
            {error ? 'Accounts could not be loaded.' : 'No local accounts.'}
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] text-sm">
              <thead>
                <tr className="border-b border-gray-100 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
                  <th className="px-5 py-3">Username</th>
                  <th className="px-5 py-3">Name</th>
                  <th className="px-5 py-3">Email</th>
                  <th className="px-5 py-3">Roles</th>
                  <th className="px-5 py-3">Status</th>
                  <th className="px-5 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {accounts.map(account => (
                  <tr key={account.id} className="hover:bg-gray-50 transition-colors">
                    <td className="px-5 py-3 text-gray-900 font-medium whitespace-nowrap">
                      {account.username}
                      {account.username === user?.username && user?.loginType === 'local' && (
                        <span className="ml-2 text-xs font-normal text-gray-400">(you)</span>
                      )}
                    </td>
                    <td className="px-5 py-3 text-gray-500 whitespace-nowrap">
                      {`${account.firstName ?? ''} ${account.lastName ?? ''}`.trim() || '—'}
                    </td>
                    <td className="px-5 py-3 text-gray-500 break-words">{account.email || '—'}</td>
                    <td className="px-5 py-3">
                      <div className="flex flex-wrap gap-1">
                        {account.roles.length === 0 ? (
                          <span className="text-gray-400">None</span>
                        ) : (
                          account.roles.map(role => (
                            <span
                              key={role}
                              className="inline-block rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600 whitespace-nowrap"
                            >
                              {roleLabelOf(role)}
                            </span>
                          ))
                        )}
                      </div>
                    </td>
                    <td className="px-5 py-3">
                      <span
                        className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap
                          ${account.enabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-400'}`}
                      >
                        {account.enabled ? 'Enabled' : 'Disabled'}
                      </span>
                    </td>
                    <td className="px-5 py-3">
                      <div className="flex flex-wrap justify-end gap-2">
                        <button
                          onClick={() => setDialog({ kind: 'password', account })}
                          className={rowButtonClass}
                        >
                          Reset password
                        </button>
                        <button
                          onClick={() => setDialog({ kind: 'roles', account })}
                          className={rowButtonClass}
                        >
                          Edit roles
                        </button>
                        <button
                          onClick={() => setDialog({ kind: 'enabled', account, enable: !account.enabled })}
                          className={rowButtonClass}
                        >
                          {account.enabled ? 'Disable' : 'Enable'}
                        </button>
                        <button
                          onClick={() => setDialog({ kind: 'delete', account })}
                          className="text-sm px-3 py-1.5 rounded-lg border border-gray-200 text-red-600 hover:bg-red-50 transition-colors"
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Add account */}
      <div className="bg-white rounded-xl border border-gray-200 p-4 sm:p-6 max-w-2xl">
        <h2 className="font-semibold text-gray-800 mb-4">Add Local Account</h2>
        <form onSubmit={addAccount} className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Username</label>
              <input
                type="text"
                required
                autoComplete="off"
                value={username}
                onChange={e => setUsername(e.target.value)}
                className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Email</label>
              <input
                type="email"
                required
                value={email}
                onChange={e => setEmail(e.target.value)}
                className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                First name <span className="font-normal text-gray-400">(optional)</span>
              </label>
              <input
                type="text"
                value={firstName}
                onChange={e => setFirstName(e.target.value)}
                className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Last name <span className="font-normal text-gray-400">(optional)</span>
              </label>
              <input
                type="text"
                value={lastName}
                onChange={e => setLastName(e.target.value)}
                className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
              />
            </div>
          </div>

          <div className="sm:max-w-[20rem]">
            <label className="block text-sm font-medium text-gray-700 mb-1">Password</label>
            <input
              type="password"
              required
              autoComplete="new-password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
            />
            <p
              className={`text-xs mt-1 ${
                password.length > 0 && password.length < MIN_PASSWORD_LENGTH ? 'text-red-600' : 'text-gray-400'
              }`}
            >
              {PASSWORD_RULE} The account starts with the Viewer role.
            </p>
            <p className="text-xs text-gray-400 mt-0.5">{PASSWORD_GUIDANCE}</p>
          </div>

          <AccountErrorNotice error={formError} />

          <button
            type="submit"
            disabled={submitting}
            className="px-4 py-2 text-sm rounded-lg bg-omnissa text-white font-medium hover:bg-omnissa-dark disabled:opacity-50 transition-colors"
          >
            {submitting ? 'Adding…' : 'Add Account'}
          </button>
        </form>
      </div>

      {dialog?.kind === 'password' && (
        <ChangePasswordDialog
          account={dialog.account}
          onClose={() => setDialog(null)}
          onDone={() => finish(`Password for "${dialog.account.username}" was reset.`)}
        />
      )}
      {dialog?.kind === 'roles' && (
        <UserRolesDialog
          account={dialog.account}
          onClose={() => setDialog(null)}
          onDone={() => finish(`Roles for "${dialog.account.username}" were updated.`)}
        />
      )}
      {dialog?.kind === 'enabled' && (
        <UserEnabledDialog
          account={dialog.account}
          enable={dialog.enable}
          onClose={() => setDialog(null)}
          onDone={() =>
            finish(`Account "${dialog.account.username}" was ${dialog.enable ? 'enabled' : 'disabled'}.`)
          }
        />
      )}
      {dialog?.kind === 'delete' && (
        <DeleteUserDialog
          account={dialog.account}
          onClose={() => setDialog(null)}
          onDeleted={() => finish(`Account "${dialog.account.username}" was deleted.`)}
        />
      )}
    </div>
  )
}
