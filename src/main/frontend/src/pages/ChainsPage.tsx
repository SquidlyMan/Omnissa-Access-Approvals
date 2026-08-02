import { useState, useEffect, useCallback } from 'react'
import { getCsrfToken } from '../utils/csrf'
import { useAuth } from '../hooks/useAuth'
import { canAdminister, FORBIDDEN_MESSAGE } from '../lib/permissions'
import type { ApprovalChain, ApprovalStage } from '../types'

const ROLE_OPTIONS = ['ROLE_ADMIN', 'ROLE_APPROVER', 'ROLE_VIEWER', 'ROLE_AUDITOR']

// A stage row being edited locally, before "Save Stages" PUTs the whole
// ordered list — matches the backend's replace-the-list-in-one-call design
// (POST /api/chains/{id}/stages), so there's no way to submit gapped/duplicate
// ordering from here either.
interface DraftStage {
  approverType: 'ROLE' | 'GROUP'
  approverValue: string
}

function describeChain(chain: ApprovalChain): string {
  const app = chain.appPattern && chain.appPattern !== '*' ? `app "${chain.appPattern}"` : 'any app'
  const group = chain.groupName ? ` from group "${chain.groupName}"` : ''
  return `Requires sequential approval for ${app}${group}`
}

function describeStage(stage: DraftStage): string {
  return stage.approverType === 'ROLE'
    ? `Anyone with the ${stage.approverValue} role`
    : `Anyone in Access group "${stage.approverValue}"`
}

export default function ChainsPage() {
  const { user } = useAuth()
  const editable = canAdminister(user)
  const [chains, setChains] = useState<ApprovalChain[]>([])
  const [error, setError] = useState('')

  const [expandedId, setExpandedId] = useState<number | null>(null)
  const [draftStages, setDraftStages] = useState<DraftStage[]>([])
  const [stagesLoading, setStagesLoading] = useState(false)
  const [stagesError, setStagesError] = useState('')
  const [stagesSaving, setStagesSaving] = useState(false)
  const [newStageType, setNewStageType] = useState<'ROLE' | 'GROUP'>('ROLE')
  const [newStageValue, setNewStageValue] = useState(ROLE_OPTIONS[0])

  // Add-chain form state
  const [name, setName] = useState('')
  const [appPattern, setAppPattern] = useState('')
  const [groupName, setGroupName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [formError, setFormError] = useState('')

  const load = useCallback(() => {
    fetch('/api/chains', { credentials: 'include' })
      .then(r => r.ok ? r.json() : Promise.reject(new Error(`Server error ${r.status}`)))
      .then((data: ApprovalChain[]) => {
        setChains([...data].sort((a, b) => a.id - b.id))
        setError('')
      })
      .catch(() => setError('Failed to load approval chains.'))
  }, [])

  useEffect(() => { load() }, [load])

  async function toggleChain(chain: ApprovalChain) {
    try {
      const res = await fetch(`/api/chains/${chain.id}`, {
        method: 'PUT',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
        body: JSON.stringify({ ...chain, enabled: !chain.enabled }),
      })
      if (res.status === 403) { setError(FORBIDDEN_MESSAGE); return }
      if (!res.ok) throw new Error(`Server error ${res.status}`)
      load()
    } catch {
      setError('Failed to update chain.')
    }
  }

  async function deleteChain(chain: ApprovalChain) {
    try {
      const res = await fetch(`/api/chains/${chain.id}`, {
        method: 'DELETE',
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': getCsrfToken() },
      })
      if (res.status === 403) { setError(FORBIDDEN_MESSAGE); return }
      if (!res.ok) throw new Error(`Server error ${res.status}`)
      if (expandedId === chain.id) setExpandedId(null)
      load()
    } catch {
      setError('Failed to delete chain.')
    }
  }

  async function addChain(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setFormError('')
    try {
      const res = await fetch('/api/chains', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
        body: JSON.stringify({
          enabled: true,
          name: name.trim(),
          appPattern: appPattern.trim() || null,
          groupName: groupName.trim() || null,
        }),
      })
      if (!res.ok) {
        let msg = res.status === 403 ? FORBIDDEN_MESSAGE : `Server error ${res.status}`
        try {
          const data = await res.json()
          if (data?.error) msg = data.error
        } catch { /* non-JSON error body */ }
        throw new Error(msg)
      }
      setName('')
      setAppPattern('')
      setGroupName('')
      load()
    } catch (err: unknown) {
      setFormError(err instanceof Error ? err.message : 'Request failed')
    } finally {
      setSubmitting(false)
    }
  }

  async function expandChain(chain: ApprovalChain) {
    if (expandedId === chain.id) {
      setExpandedId(null)
      return
    }
    setExpandedId(chain.id)
    setStagesLoading(true)
    setStagesError('')
    try {
      const res = await fetch(`/api/chains/${chain.id}/stages`, { credentials: 'include' })
      if (!res.ok) throw new Error(`Server error ${res.status}`)
      const data: ApprovalStage[] = await res.json()
      setDraftStages(data
        .sort((a, b) => a.stageOrder - b.stageOrder)
        .map(s => ({ approverType: s.approverType, approverValue: s.approverValue })))
    } catch {
      setStagesError('Failed to load stages.')
      setDraftStages([])
    } finally {
      setStagesLoading(false)
    }
  }

  function addDraftStage() {
    if (!newStageValue.trim()) return
    setDraftStages(prev => [...prev, { approverType: newStageType, approverValue: newStageValue.trim() }])
    if (newStageType === 'GROUP') setNewStageValue('')
  }

  function removeDraftStage(index: number) {
    setDraftStages(prev => prev.filter((_, i) => i !== index))
  }

  function moveDraftStage(index: number, direction: -1 | 1) {
    setDraftStages(prev => {
      const next = [...prev]
      const target = index + direction
      if (target < 0 || target >= next.length) return prev
      ;[next[index], next[target]] = [next[target], next[index]]
      return next
    })
  }

  async function saveStages(chainId: number) {
    setStagesSaving(true)
    setStagesError('')
    try {
      const res = await fetch(`/api/chains/${chainId}/stages`, {
        method: 'PUT',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
        body: JSON.stringify(draftStages),
      })
      if (!res.ok) {
        let msg = res.status === 403 ? FORBIDDEN_MESSAGE : `Server error ${res.status}`
        try {
          const data = await res.json()
          if (data?.error) msg = data.error
        } catch { /* non-JSON error body */ }
        throw new Error(msg)
      }
    } catch (err: unknown) {
      setStagesError(err instanceof Error ? err.message : 'Failed to save stages.')
    } finally {
      setStagesSaving(false)
    }
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold text-gray-900 mb-2">Approval Chains</h1>
      <p className="text-sm text-gray-500 mb-2 max-w-3xl">
        A chain requires sequential approval by different stages before a request reaches
        Omnissa Access — an app that matches a chain's app name / group needs every stage's
        approval, in order, rather than any one approver deciding it. Rejecting at any stage
        rejects the whole request immediately.
      </p>
      <p className="text-xs text-gray-400 mb-6 max-w-3xl">
        A matched request is exempt from Auto-Approval Rules — a chain exists specifically to
        require sequential human judgment. Each stage requires either anyone holding a role, or
        anyone in a specific Access group; a stage nobody can satisfy makes the chain unusable, so
        a chain with no stages is never matched.
      </p>

      {error && (
        <div className="mb-4 rounded-lg bg-red-50 border border-red-200 px-4 py-2 text-sm text-red-700">
          {error}
        </div>
      )}

      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden mb-6">
        {chains.length === 0 ? (
          <p className="text-sm text-gray-400 px-5 py-8 text-center">No approval chains defined yet.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {chains.map(chain => (
              <li key={chain.id}>
                <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-4 hover:bg-gray-50 transition-colors">
                  <button
                    onClick={() => expandChain(chain)}
                    className="flex-1 min-w-[10rem] text-left"
                  >
                    <p className={`font-medium truncate ${chain.enabled ? 'text-gray-900' : 'text-gray-400'}`}>
                      {chain.name}
                    </p>
                    <p className="text-xs text-gray-500 truncate">{describeChain(chain)}</p>
                    {!chain.enabled && <p className="text-xs text-gray-400">Disabled</p>}
                  </button>
                  <button
                    onClick={() => expandChain(chain)}
                    className="text-xs text-gray-500 underline shrink-0"
                  >
                    {expandedId === chain.id ? 'Hide stages' : 'Edit stages'}
                  </button>
                  {editable && (
                    <div className="shrink-0 flex items-center gap-3">
                      <button
                        onClick={() => toggleChain(chain)}
                        role="switch"
                        aria-checked={chain.enabled}
                        title={chain.enabled ? 'Disable chain' : 'Enable chain'}
                        className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors
                          ${chain.enabled ? 'bg-green-500' : 'bg-gray-300'}`}
                      >
                        <span
                          className={`inline-block h-4 w-4 rounded-full bg-white shadow transform transition-transform
                            ${chain.enabled ? 'translate-x-6' : 'translate-x-1'}`}
                        />
                      </button>
                      <button
                        onClick={() => deleteChain(chain)}
                        className="text-sm px-3 py-1.5 rounded-lg border border-gray-200 text-red-600 hover:bg-red-50 transition-colors"
                      >
                        Delete
                      </button>
                    </div>
                  )}
                </div>

                {expandedId === chain.id && (
                  <div className="px-5 pb-5 bg-gray-50 border-t border-gray-100">
                    {stagesLoading ? (
                      <p className="text-sm text-gray-400 py-4">Loading stages…</p>
                    ) : (
                      <>
                        {stagesError && <p className="text-sm text-red-600 py-2">{stagesError}</p>}
                        {draftStages.length === 0 ? (
                          <p className="text-sm text-gray-400 py-4">
                            No stages yet — this chain will never be matched until it has at least one.
                          </p>
                        ) : (
                          <ol className="py-3 space-y-2">
                            {draftStages.map((stage, i) => (
                              <li key={i} className="flex items-center gap-3 bg-white rounded-lg border border-gray-200 px-3 py-2">
                                <span className="text-xs font-medium text-gray-400 w-14 shrink-0">Stage {i + 1}</span>
                                <span className="flex-1 text-sm text-gray-700">{describeStage(stage)}</span>
                                {editable && (
                                  <div className="flex items-center gap-1 shrink-0">
                                    <button
                                      onClick={() => moveDraftStage(i, -1)}
                                      disabled={i === 0}
                                      title="Move earlier"
                                      className="px-2 py-1 text-xs rounded border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-30"
                                    >
                                      ↑
                                    </button>
                                    <button
                                      onClick={() => moveDraftStage(i, 1)}
                                      disabled={i === draftStages.length - 1}
                                      title="Move later"
                                      className="px-2 py-1 text-xs rounded border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-30"
                                    >
                                      ↓
                                    </button>
                                    <button
                                      onClick={() => removeDraftStage(i)}
                                      className="px-2 py-1 text-xs rounded border border-gray-200 text-red-600 hover:bg-red-50"
                                    >
                                      Remove
                                    </button>
                                  </div>
                                )}
                              </li>
                            ))}
                          </ol>
                        )}

                        {editable && (
                          <>
                            <div className="flex flex-wrap items-end gap-3 py-3 border-t border-gray-200">
                              <div>
                                <label className="block text-xs font-medium text-gray-700 mb-1">Type</label>
                                <select
                                  value={newStageType}
                                  onChange={e => {
                                    const t = e.target.value as 'ROLE' | 'GROUP'
                                    setNewStageType(t)
                                    setNewStageValue(t === 'ROLE' ? ROLE_OPTIONS[0] : '')
                                  }}
                                  className="rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                                >
                                  <option value="ROLE">Role</option>
                                  <option value="GROUP">Access group</option>
                                </select>
                              </div>
                              <div className="flex-1 min-w-[10rem]">
                                <label className="block text-xs font-medium text-gray-700 mb-1">
                                  {newStageType === 'ROLE' ? 'Role' : 'Access group id'}
                                </label>
                                {newStageType === 'ROLE' ? (
                                  <select
                                    value={newStageValue}
                                    onChange={e => setNewStageValue(e.target.value)}
                                    className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                                  >
                                    {ROLE_OPTIONS.map(r => <option key={r} value={r}>{r}</option>)}
                                  </select>
                                ) : (
                                  <input
                                    type="text"
                                    value={newStageValue}
                                    onChange={e => setNewStageValue(e.target.value)}
                                    placeholder="Read the id from /api/auth/claims after signing in"
                                    className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                                  />
                                )}
                              </div>
                              <button
                                onClick={addDraftStage}
                                className="px-4 py-2 text-sm rounded-lg border border-gray-200 text-gray-700 hover:bg-gray-50 transition-colors"
                              >
                                Add Stage
                              </button>
                            </div>
                            <button
                              onClick={() => saveStages(chain.id)}
                              disabled={stagesSaving || draftStages.length === 0}
                              className="px-4 py-2 text-sm rounded-lg bg-omnissa text-white font-medium hover:bg-omnissa-dark disabled:opacity-50 transition-colors"
                            >
                              {stagesSaving ? 'Saving…' : 'Save Stages'}
                            </button>
                          </>
                        )}
                      </>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {editable && (
        <div className="bg-white rounded-xl border border-gray-200 p-4 sm:p-6 max-w-2xl">
          <h2 className="font-semibold text-gray-800 mb-4">Add Chain</h2>
          <form onSubmit={addChain} className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-3">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
                <input
                  type="text"
                  required
                  value={name}
                  onChange={e => setName(e.target.value)}
                  placeholder="e.g. Finance apps"
                  className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-omnissa"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  App name pattern <span className="font-normal text-gray-400">(optional)</span>
                </label>
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
            <p className="text-xs text-gray-400">
              At least one of app name pattern / group is required — a chain with neither matches
              nothing, the same as an empty Auto-Approval Rule.
            </p>

            {formError && <p className="text-red-600 text-sm">{formError}</p>}

            <button
              type="submit"
              disabled={submitting}
              className="px-4 py-2 text-sm rounded-lg bg-omnissa text-white font-medium hover:bg-omnissa-dark disabled:opacity-50 transition-colors"
            >
              {submitting ? 'Adding…' : 'Add Chain'}
            </button>
          </form>
        </div>
      )}
    </div>
  )
}
