import { useState, useEffect, useCallback } from 'react'
import { getCsrfToken } from '../utils/csrf'
import { useAuth } from '../hooks/useAuth'
import { canAdminister, FORBIDDEN_MESSAGE } from '../lib/permissions'
import type { Rule } from '../types'

// Time-bound (JIT) grant options for approve rules. '' = permanent.
const GRANT_TTL_OPTIONS: { label: string; minutes: number | null }[] = [
  { label: 'Permanent', minutes: null },
  { label: '5 minutes', minutes: 5 },
  { label: '15 minutes', minutes: 15 },
  { label: '1 hour', minutes: 60 },
  { label: '8 hours', minutes: 480 },
  { label: '24 hours', minutes: 1440 },
  { label: '7 days', minutes: 10080 },
  { label: '30 days', minutes: 43200 },
]

// Escalation intervals (#51). '' = off.
const ESCALATE_AFTER_OPTIONS: { label: string; minutes: number | null }[] = [
  { label: 'Off', minutes: null },
  { label: '15 minutes', minutes: 15 },
  { label: '1 hour', minutes: 60 },
  { label: '4 hours', minutes: 240 },
  { label: '8 hours', minutes: 480 },
  { label: '24 hours', minutes: 1440 },
  { label: '2 days', minutes: 2880 },
]

function describeDuration(minutes: number): string {
  if (minutes % 1440 === 0) { const d = minutes / 1440; return `${d} day${d === 1 ? '' : 's'}` }
  if (minutes % 60 === 0) { const h = minutes / 60; return `${h} hour${h === 1 ? '' : 's'}` }
  return `${minutes} min`
}

function describeRule(rule: Rule): string {
  if (rule.expiryDays != null) {
    // State the scope. An expiry rule with criteria rejects far less than one
    // without, and the rule row is the only place that is visible.
    const scope = rule.appPattern && rule.appPattern !== '*' ? ` for app "${rule.appPattern}"` : ''
    const group = rule.groupName ? ` from group "${rule.groupName}"` : ''
    const days = `${rule.expiryDays} day${rule.expiryDays === 1 ? '' : 's'}`
    // The whole policy on one line — escalation is otherwise invisible until
    // it fires, and this row is the only place it can be read.
    const nudge = rule.escalateAfterMinutes != null
      ? `Nudge after ${describeDuration(rule.escalateAfterMinutes)}, then auto-reject`
      : 'Auto-reject'
    return `${nudge} requests${scope}${group} pending longer than ${days}`
  }
  const verb = rule.action === 'approve' ? 'Auto-approve' : 'Auto-reject'
  const app = rule.appPattern && rule.appPattern !== '*' ? `app "${rule.appPattern}"` : 'any app'
  const group = rule.groupName ? ` from group "${rule.groupName}"` : ''
  const ttl = rule.action === 'approve' && rule.grantTtlMinutes != null
    ? ` for ${describeDuration(rule.grantTtlMinutes)}`
    : ''
  return `${verb} requests for ${app}${group}${ttl}`
}

export default function RulesPage() {
  const { user } = useAuth()
  const editable = canAdminister(user)
  const [rules, setRules] = useState<Rule[]>([])
  const [error, setError] = useState('')

  // Add-rule form state
  const [ruleType, setRuleType] = useState<'match' | 'expiry'>('match')
  const [action, setAction] = useState<'approve' | 'reject'>('approve')
  const [appPattern, setAppPattern] = useState('')
  const [groupName, setGroupName] = useState('')
  const [grantTtl, setGrantTtl] = useState<number | null>(null)
  const [expiryDays, setExpiryDays] = useState('')
  const [escalateAfter, setEscalateAfter] = useState<number | null>(null)
  const [claimTtl, setClaimTtl] = useState<number | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [formError, setFormError] = useState('')

  const load = useCallback(() => {
    fetch('/api/rules', { credentials: 'include' })
      .then(r => r.ok ? r.json() : Promise.reject(new Error(`Server error ${r.status}`)))
      .then((data: Rule[]) => {
        // Rules are evaluated in ascending id order — display them the same way.
        setRules([...data].sort((a, b) => a.id - b.id))
        setError('')
      })
      .catch(() => setError('Failed to load rules.'))
  }, [])

  useEffect(() => { load() }, [load])

  async function toggleRule(rule: Rule) {
    try {
      const res = await fetch(`/api/rules/${rule.id}`, {
        method: 'PUT',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
        body: JSON.stringify({
          enabled: !rule.enabled,
          action: rule.action,
          appPattern: rule.appPattern,
          groupName: rule.groupName,
          expiryDays: rule.expiryDays,
          grantTtlMinutes: rule.grantTtlMinutes,
          // Carried through deliberately: this is a full replacement, so
          // omitting them would silently wipe a rule's escalation settings
          // every time somebody flicked the enable toggle.
          escalateAfterMinutes: rule.escalateAfterMinutes ?? null,
          claimTtlMinutes: rule.claimTtlMinutes ?? null,
        }),
      })
      if (res.status === 403) { setError(FORBIDDEN_MESSAGE); return }
      if (!res.ok) throw new Error(`Server error ${res.status}`)
      load()
    } catch {
      setError('Failed to update rule.')
    }
  }

  async function deleteRule(rule: Rule) {
    try {
      const res = await fetch(`/api/rules/${rule.id}`, {
        method: 'DELETE',
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': getCsrfToken() },
      })
      if (res.status === 403) { setError(FORBIDDEN_MESSAGE); return }
      if (!res.ok) throw new Error(`Server error ${res.status}`)
      load()
    } catch {
      setError('Failed to delete rule.')
    }
  }

  async function addRule(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setFormError('')
    const body = ruleType === 'match'
      ? { enabled: true, action, appPattern: appPattern.trim() || null, groupName: groupName.trim() || null,
          expiryDays: null, grantTtlMinutes: action === 'approve' ? grantTtl : null }
      : { enabled: true, action: 'reject', appPattern: appPattern.trim() || null, groupName: groupName.trim() || null,
          expiryDays: Number(expiryDays), grantTtlMinutes: null,
          escalateAfterMinutes: escalateAfter, claimTtlMinutes: claimTtl }
    try {
      const res = await fetch('/api/rules', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
        body: JSON.stringify(body),
      })
      if (!res.ok) {
        let msg = res.status === 403 ? FORBIDDEN_MESSAGE : `Server error ${res.status}`
        try {
          const data = await res.json()
          if (data?.error) msg = data.error
        } catch { /* non-JSON error body */ }
        throw new Error(msg)
      }
      setAppPattern('')
      setGroupName('')
      setGrantTtl(null)
      setExpiryDays('')
      setEscalateAfter(null)
      setClaimTtl(null)
      load()
    } catch (err: unknown) {
      setFormError(err instanceof Error ? err.message : 'Request failed')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold text-gray-900 mb-2">Auto-Approval Rules</h1>
      <p className="text-sm text-gray-500 mb-2 max-w-3xl">
        Rules automatically approve or reject incoming access requests by application name and
        group membership, or auto-reject requests that stay pending too long. All rule decisions
        are recorded in the Audit trail.
      </p>
      <p className="text-xs text-gray-400 mb-6 max-w-3xl">
        Rules are evaluated in order — the first enabled rule that matches a request wins. Expiry
        rules run independently on an hourly schedule.
      </p>

      {error && (
        <div className="mb-4 rounded-lg bg-red-50 border border-red-200 px-4 py-2 text-sm text-red-700">
          {error}
        </div>
      )}

      {/* Rule list */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden mb-6">
        {rules.length === 0 ? (
          <p className="text-sm text-gray-400 px-5 py-8 text-center">No rules defined yet.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {rules.map(rule => (
              <li key={rule.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-4 hover:bg-gray-50 transition-colors">
                <span
                  className={`shrink-0 inline-block rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap
                    ${rule.enabled ? 'bg-gray-100 text-gray-600' : 'bg-gray-100 text-gray-400'}`}
                  title="Rule number — rules are evaluated in ascending order"
                >
                  #{rule.id}
                </span>
                <div className="flex-1 min-w-[10rem]">
                  <p className={`font-medium truncate ${rule.enabled ? 'text-gray-900' : 'text-gray-400'}`}>
                    {describeRule(rule)}
                  </p>
                  {!rule.enabled && <p className="text-xs text-gray-400">Disabled</p>}
                </div>
                {editable && (
                <div className="shrink-0 flex items-center gap-3">
                  <button
                    onClick={() => toggleRule(rule)}
                    role="switch"
                    aria-checked={rule.enabled}
                    title={rule.enabled ? 'Disable rule' : 'Enable rule'}
                    className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors
                      ${rule.enabled ? 'bg-green-500' : 'bg-gray-300'}`}
                  >
                    <span
                      className={`inline-block h-4 w-4 rounded-full bg-white shadow transform transition-transform
                        ${rule.enabled ? 'translate-x-6' : 'translate-x-1'}`}
                    />
                  </button>
                  <button
                    onClick={() => deleteRule(rule)}
                    className="text-sm px-3 py-1.5 rounded-lg border border-gray-200 text-red-600 hover:bg-red-50 transition-colors"
                  >
                    Delete
                  </button>
                </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Add rule form — administrators only; everyone else sees the list read-only. */}
      {editable && (
      <div className="bg-white rounded-xl border border-gray-200 p-4 sm:p-6 max-w-2xl">
        <h2 className="font-semibold text-gray-800 mb-4">Add Rule</h2>
        <form onSubmit={addRule} className="space-y-4">
          <div className="flex gap-6 text-sm text-gray-700">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="radio"
                name="ruleType"
                checked={ruleType === 'match'}
                onChange={() => setRuleType('match')}
                className="accent-omnissa"
              />
              Match rule
            </label>
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="radio"
                name="ruleType"
                checked={ruleType === 'expiry'}
                onChange={() => setRuleType('expiry')}
                className="accent-omnissa"
              />
              Expiry rule
            </label>
          </div>

          {ruleType === 'match' ? (
            <>
            <div className="grid gap-4 sm:grid-cols-3">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Action</label>
                <select
                  value={action}
                  onChange={e => setAction(e.target.value as 'approve' | 'reject')}
                  className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                >
                  <option value="approve">Approve</option>
                  <option value="reject">Reject</option>
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">App name pattern</label>
                <input
                  type="text"
                  value={appPattern}
                  onChange={e => setAppPattern(e.target.value)}
                  placeholder="e.g. My App or * for any"
                  className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Group name <span className="font-normal text-gray-400">(optional)</span>
                </label>
                <input
                  type="text"
                  value={groupName}
                  onChange={e => setGroupName(e.target.value)}
                  className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                />
              </div>
            </div>
            {action === 'approve' && (
              <div className="sm:max-w-[16rem]">
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Grant duration <span className="font-normal text-gray-400">(JIT)</span>
                </label>
                <select
                  value={grantTtl ?? ''}
                  onChange={e => setGrantTtl(e.target.value === '' ? null : Number(e.target.value))}
                  className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                >
                  {GRANT_TTL_OPTIONS.map(o => (
                    <option key={o.label} value={o.minutes ?? ''}>{o.label}</option>
                  ))}
                </select>
                <p className="text-xs text-gray-400 mt-1">
                  Auto-approved access is revoked after this time.
                </p>
              </div>
            )}
            </>
          ) : (
            <div className="grid gap-4 sm:grid-cols-3">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Days pending</label>
                <input
                  type="number"
                  min={1}
                  required
                  value={expiryDays}
                  onChange={e => setExpiryDays(e.target.value)}
                  className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                />
                <p className="text-xs text-gray-400 mt-1">Requests pending longer than this are auto-rejected.</p>
              </div>
              {/* Leave both blank to expire every stale request — that is the
                  ordinary expiry rule. Filling either narrows it. Before the
                  matcher fix the sweep ignored these entirely, so a scoped rule
                  silently rejected everything. */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  App name pattern <span className="font-normal text-gray-400">(optional)</span>
                </label>
                <input
                  type="text"
                  value={appPattern}
                  onChange={e => setAppPattern(e.target.value)}
                  placeholder="blank = every app"
                  className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Group name <span className="font-normal text-gray-400">(optional)</span>
                </label>
                <input
                  type="text"
                  value={groupName}
                  onChange={e => setGroupName(e.target.value)}
                  placeholder="blank = every group"
                  className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                />
              </div>
            </div>
          )}

          {/* Escalation rides on the expiry rule rather than living in its own
              policy object — one rule reads "nudge at 4 hours, reject at 3
              days". Visually separated so it still reads as a distinct concern
              at zero schema and documentation cost. */}
          {ruleType === 'expiry' && (
            <details className="rounded-lg border border-gray-200 px-4 py-3">
              <summary className="text-sm font-medium text-gray-700 cursor-pointer">
                Escalation <span className="font-normal text-gray-400">(optional)</span>
              </summary>
              <div className="grid gap-4 sm:grid-cols-2 mt-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Nudge after</label>
                  <select
                    value={escalateAfter ?? ''}
                    onChange={e => setEscalateAfter(e.target.value === '' ? null : Number(e.target.value))}
                    className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                  >
                    {ESCALATE_AFTER_OPTIONS.map(o => (
                      <option key={o.label} value={o.minutes ?? ''}>{o.label}</option>
                    ))}
                  </select>
                  <p className="text-xs text-gray-400 mt-1">
                    Notifies the chat channel and the approvers themselves. Must be shorter than
                    the rejection window above, or it could never fire.
                  </p>
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Release an unactioned claim after
                  </label>
                  <select
                    value={claimTtl ?? ''}
                    onChange={e => setClaimTtl(e.target.value === '' ? null : Number(e.target.value))}
                    className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                  >
                    <option value="">Same as the nudge interval</option>
                    {ESCALATE_AFTER_OPTIONS.filter(o => o.minutes != null).map(o => (
                      <option key={o.label} value={o.minutes ?? ''}>{o.label}</option>
                    ))}
                  </select>
                  <p className="text-xs text-gray-400 mt-1">
                    An abandoned claim reads as "handled" to everyone else, which is worse than no
                    claim at all — so it lapses.
                  </p>
                </div>
              </div>
            </details>
          )}

          {formError && <p className="text-red-600 text-sm">{formError}</p>}

          <button
            type="submit"
            disabled={submitting}
            className="px-4 py-2 text-sm rounded-lg bg-omnissa text-white font-medium hover:bg-omnissa-dark disabled:opacity-50 transition-colors"
          >
            {submitting ? 'Adding…' : 'Add Rule'}
          </button>
        </form>
      </div>
      )}
    </div>
  )
}
