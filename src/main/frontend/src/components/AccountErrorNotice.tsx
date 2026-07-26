import type { AccountError } from '../lib/accounts'

interface Props {
  error: AccountError | null
  /** Extra context for a refusal, e.g. why the last admin is protected. */
  hint?: string
}

/**
 * Shows an account-management failure. A refusal (409/400) is not a bug: the
 * server is stating a rule — usually that the change would remove the last way
 * back into the tool — so it gets a full callout carrying the server's own
 * wording, not the one-line red text used for ordinary errors.
 */
export default function AccountErrorNotice({ error, hint }: Props) {
  if (!error) return null

  if (!error.refused) {
    return <p className="text-red-600 text-sm mb-3">{error.message}</p>
  }

  return (
    <div
      role="alert"
      className="mb-3 rounded-lg bg-amber-50 border border-amber-300 px-3 py-2.5 text-sm text-amber-900 space-y-1.5"
    >
      <p className="font-semibold">Refused — nothing was changed</p>
      <p>{error.message}</p>
      {hint && <p className="text-xs text-amber-700">{hint}</p>}
    </div>
  )
}
