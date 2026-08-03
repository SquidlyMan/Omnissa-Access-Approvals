import { useState, useEffect } from 'react'
import { getCsrfToken } from '../utils/csrf'
import { FORBIDDEN_MESSAGE } from '../lib/permissions'
import type { Approver, CalloutRequest } from '../types'

/** Local-part of an email, so a badge never publishes a full address. */
export function ownerLabel(owner: string): string {
  const at = owner.indexOf('@')
  return at > 0 ? owner.slice(0, at) : owner
}

function describeAge(iso: string | null | undefined): string {
  if (!iso) return ''
  const minutes = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000))
  if (minutes < 60) return `${minutes} min`
  const hours = Math.round(minutes / 60)
  if (hours < 48) return `${hours} hour${hours === 1 ? '' : 's'}`
  return `${Math.round(hours / 24)} days`
}

interface Props {
  request: CalloutRequest
  onChanged: (fresh: CalloutRequest) => void
}

/**
 * Claim / Release / Assign / Escalate now (#51).
 *
 * <p>Ownership here is <strong>advisory</strong>: it changes who the queue
 * shows as holding a request, never who may decide it. The Approve and Reject
 * controls stay enabled for every approver regardless of who holds it — that
 * is design decision D1, and it is asserted at the API level too, because the
 * realistic way it dies is a well-meaning UI change that hides the button.
 */
export default function DelegationPanel({ request, onChanged }: Props) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [approvers, setApprovers] = useState<Approver[]>([])
  const [assignee, setAssignee] = useState('')
  const [showAssign, setShowAssign] = useState(false)

  useEffect(() => {
    if (!showAssign || approvers.length > 0) return
    fetch('/api/approvals/approvers', { credentials: 'include' })
      .then(r => (r.ok ? r.json() : []))
      .then((data: Approver[]) => setApprovers(data))
      .catch(() => setApprovers([]))
  }, [showAssign, approvers.length])

  async function act(path: string, body?: unknown) {
    setBusy(true)
    setError('')
    try {
      const res = await fetch(`/api/approvals/requests/${request.requestId}/${path}`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'X-XSRF-TOKEN': getCsrfToken(),
          ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        ...(body ? { body: JSON.stringify(body) } : {}),
      })
      if (res.status === 403) { setError(FORBIDDEN_MESSAGE); return }
      if (!res.ok) {
        const data = await res.json().catch(() => ({}))
        throw new Error(data?.error || `Server error ${res.status}`)
      }
      const fresh = await fetch(`/api/approvals/requests/${request.requestId}`, { credentials: 'include' })
        .then(r => (r.ok ? r.json() : null))
      if (fresh) onChanged(fresh)
      setShowAssign(false)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Request failed')
    } finally {
      setBusy(false)
    }
  }

  const owner = request.assignedOwner
  const escalated = (request.escalationStage ?? 0) >= 1

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-5 mb-4">
      <h2 className="font-semibold text-gray-800 mb-3">Ownership</h2>

      <div className="flex flex-wrap items-center gap-3 mb-3">
        {owner ? (
          <span className="inline-flex items-center gap-2 text-sm text-gray-700">
            <span className="inline-block rounded-full bg-blue-50 text-blue-700 px-2.5 py-0.5 text-xs font-medium">
              {ownerLabel(owner)}
            </span>
            holds this request
            {request.assignedAt && (
              <span className="text-gray-400">· {describeAge(request.assignedAt)}</span>
            )}
          </span>
        ) : (
          <span className="text-sm text-gray-500">Nobody has claimed this request.</span>
        )}
        {escalated && (
          <span className="inline-block rounded-full bg-amber-100 text-amber-800 px-2.5 py-0.5 text-xs font-medium">
            ⏰ Escalated{request.escalatedAt ? ` · ${describeAge(request.escalatedAt)} ago` : ''}
          </span>
        )}
      </div>

      <p className="text-xs text-gray-400 mb-4">
        Ownership is advisory. Any approver can decide this request whoever holds it — a claim
        makes work visible, it never blocks a decision.
      </p>

      <div className="flex flex-wrap gap-2">
        {!owner && (
          <button
            onClick={() => act('claim')}
            disabled={busy}
            className="text-sm px-3 py-1.5 rounded-lg border border-gray-200 text-gray-700 hover:bg-gray-50 disabled:opacity-50 transition-colors"
          >
            Claim
          </button>
        )}
        {owner && (
          <button
            onClick={() => act('release')}
            disabled={busy}
            className="text-sm px-3 py-1.5 rounded-lg border border-gray-200 text-gray-700 hover:bg-gray-50 disabled:opacity-50 transition-colors"
          >
            Release
          </button>
        )}
        <button
          onClick={() => setShowAssign(v => !v)}
          disabled={busy}
          className="text-sm px-3 py-1.5 rounded-lg border border-gray-200 text-gray-700 hover:bg-gray-50 disabled:opacity-50 transition-colors"
        >
          {owner ? 'Reassign…' : 'Assign…'}
        </button>
        {!escalated && (
          <button
            onClick={() => act('escalate')}
            disabled={busy}
            title="Notify the chat channel and the approvers now, without waiting for the timer"
            className="text-sm px-3 py-1.5 rounded-lg border border-amber-300 text-amber-800 hover:bg-amber-50 disabled:opacity-50 transition-colors"
          >
            Escalate now
          </button>
        )}
      </div>

      {showAssign && (
        <div className="mt-4 pt-4 border-t border-gray-100">
          <label className="block text-sm font-medium text-gray-700 mb-1">Assign to</label>
          <div className="flex flex-wrap gap-2">
            <select
              value={assignee}
              onChange={e => setAssignee(e.target.value)}
              className="flex-1 min-w-[14rem] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
            >
              <option value="">
                {approvers.length ? 'Choose an approver…' : 'No approvers resolved'}
              </option>
              {approvers.map(a => (
                <option key={a.identity} value={a.identity}>
                  {a.displayName ? `${a.displayName} (${a.identity})` : a.identity}
                </option>
              ))}
            </select>
            <button
              onClick={() => act('assign', { assignee })}
              disabled={busy || !assignee}
              className="px-4 py-2 text-sm rounded-lg bg-omnissa text-white font-medium hover:bg-omnissa-dark disabled:opacity-50 transition-colors"
            >
              Assign
            </button>
          </div>
          <p className="text-xs text-gray-400 mt-2">
            Approvers are resolved live from the Omnissa Access groups mapped to the Approver and
            Admin roles — there is no separate list to maintain. Assigning does not oblige them:
            escalation still fires on schedule and an unactioned assignment is released
            automatically.
          </p>
        </div>
      )}

      {error && <p className="text-red-600 text-sm mt-3">{error}</p>}
    </div>
  )
}
