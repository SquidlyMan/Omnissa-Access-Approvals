import { useCallback, useEffect, useState } from 'react'
import type { UpdateView } from '../types'
import { getCsrfToken } from '../utils/csrf'

/**
 * Update detection and approval (#83) — the always-on surface.
 *
 * Detection is one thing; approval is a separate, deliberate act, and only an
 * administrator can take it. Approving writes a request for the host-side
 * updater; nothing here installs anything, because the container cannot
 * restart itself without being handed the Docker socket.
 *
 * Reads the last-known state on load and never hits the registry from a page
 * render. "Check now" is the one action that does.
 */
export default function UpdateBanner({ isAdmin }: { isAdmin: boolean }) {
  const [view, setView] = useState<UpdateView | null>(null)
  const [checking, setChecking] = useState(false)
  const [picking, setPicking] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    fetch('/api/updates/status', { credentials: 'include' })
      .then(r => (r.ok ? r.json() : null))
      .then((v: UpdateView | null) => { if (v) setView(v) })
      .catch(() => { /* informational; a failed read shows nothing */ })
  }, [])

  useEffect(() => { load() }, [load])

  async function checkNow() {
    setChecking(true)
    setError('')
    try {
      const r = await fetch('/api/updates/check', {
        method: 'POST', credentials: 'include', headers: { 'X-XSRF-TOKEN': getCsrfToken() },
      })
      if (!r.ok) throw new Error(`Server error ${r.status}`)
      setView(await r.json())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Check failed')
    } finally {
      setChecking(false)
    }
  }

  if (!view || !view.detection.enabled) return null
  const d = view.detection
  const checked = d.lastCheckedAt ? formatDate(d.lastCheckedAt) : 'never'

  // A deploy has been approved and the host has not picked it up yet. Show that
  // above everything else — a second approval while one is pending is confusing.
  if (view.pendingTarget) {
    return (
      <div className="mb-6 rounded-xl border border-blue-300 bg-blue-50 px-5 py-4">
        <p className="font-semibold text-blue-900">
          Deployment of {view.pendingTarget} approved
          <span className="font-normal text-blue-800"> — waiting for the host to apply it</span>
        </p>
        <p className="text-xs text-blue-800 mt-0.5">
          Requested {view.pendingSince ? formatDate(view.pendingSince) : ''}. The container will restart when the
          updater runs; this page will stop responding briefly and come back on the new version.
        </p>
      </div>
    )
  }

  const controls = isAdmin && (
    <div className="shrink-0 flex items-center gap-2">
      <button onClick={checkNow} disabled={checking}
        className="px-3 py-1.5 text-sm rounded-lg border border-gray-300 bg-white text-gray-800 hover:bg-gray-50 disabled:opacity-50">
        {checking ? 'Checking…' : 'Check now'}
      </button>
      <button onClick={() => setPicking(true)} disabled={view.knownVersions.length === 0}
        className="px-3 py-1.5 text-sm rounded-lg bg-omnissa text-white font-medium hover:bg-omnissa-dark disabled:opacity-50">
        {d.updateAvailable ? 'Approve…' : 'Choose version…'}
      </button>
    </div>
  )

  return (
    <>
      {d.updateAvailable ? (
        <div className="mb-6 rounded-xl border border-amber-300 bg-amber-50 px-5 py-4 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div className="min-w-0">
            <p className="font-semibold text-amber-900">
              Update available — {d.newestVersion}
              <span className="font-normal text-amber-800"> (running {d.runningVersion})</span>
            </p>
            <p className="text-xs text-amber-800 mt-0.5">
              Last checked {checked}
              {d.lastError && <> · last check failed: {d.lastError}</>}
            </p>
          </div>
          {controls}
        </div>
      ) : (
        <div className="mb-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 text-xs text-gray-400">
          <span>
            {d.newestVersion
              ? <>You're up to date · {d.runningVersion} · last checked {checked}</>
              : <>Update check has not completed yet · last checked {checked}</>}
            {d.lastError && <span className="text-orange-600"> · last check failed: {d.lastError}</span>}
          </span>
          {controls}
        </div>
      )}
      {error && <p className="-mt-4 mb-4 text-xs text-red-600">{error}</p>}
      {picking && (
        <ApproveDialog
          view={view}
          onClose={() => setPicking(false)}
          onApproved={v => { setView(v); setPicking(false) }}
        />
      )}
    </>
  )
}

/**
 * Choose a version and hand it to the host. Any published version is
 * selectable, including older ones for rollback — but below the floor the
 * dialog names what the rollback would reopen and requires the version to be
 * typed again. That floor is a constant in the server, not a setting.
 */
function ApproveDialog({ view, onClose, onApproved }: {
  view: UpdateView
  onClose: () => void
  onApproved: (v: UpdateView) => void
}) {
  const d = view.detection
  const [target, setTarget] = useState(d.newestVersion ?? view.knownVersions[0] ?? '')
  const [confirmation, setConfirmation] = useState('')
  const [reopened, setReopened] = useState<string[]>([])
  const [needsConfirmation, setNeedsConfirmation] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const belowFloor = compareSemver(target, view.rollbackFloor) < 0
  const isDowngrade = compareSemver(target, d.runningVersion) < 0

  async function submit() {
    setBusy(true)
    setError('')
    try {
      const r = await fetch('/api/updates/approve', {
        method: 'POST', credentials: 'include',
        headers: { 'X-XSRF-TOKEN': getCsrfToken(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ target, confirmation: confirmation || null }),
      })
      const body = await r.json().catch(() => ({}))
      if (r.status === 409 && body.confirmationRequired) {
        setNeedsConfirmation(true)
        setReopened(body.reopened ?? [])
        setError(body.error ?? '')
        return
      }
      if (!r.ok) throw new Error(body.error ?? `Server error ${r.status}`)
      onApproved(body.view as UpdateView)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Approval failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="bg-white rounded-xl shadow-xl w-full max-w-lg p-6" onClick={e => e.stopPropagation()}>
        <h2 className="font-semibold text-gray-900 text-lg">Approve a deployment</h2>
        <p className="text-sm text-gray-600 mt-1">
          The host-side updater will pull the chosen version, recreate the container, and verify it came up.
          Running <span className="font-medium text-gray-800">{d.runningVersion}</span>.
        </p>

        {!view.controlDirectoryMounted && (
          <div className="mt-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
            The control directory is not mounted, so the host cannot see approvals. Add the
            <code className="mx-1 px-1 rounded bg-red-100">/app/control</code> mount to the compose file and restart.
          </div>
        )}

        <label className="block text-sm font-medium text-gray-700 mt-4 mb-1">Version</label>
        <select value={target} onChange={e => { setTarget(e.target.value); setNeedsConfirmation(false); setReopened([]); setError('') }}
          className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-omnissa">
          {view.knownVersions.map(v => (
            <option key={v} value={v}>
              {v}{v === d.newestVersion ? ' — newest' : ''}{v === d.runningVersion ? ' — running' : ''}
            </option>
          ))}
        </select>

        {isDowngrade && !belowFloor && (
          <p className="mt-2 text-xs text-amber-700">This is a rollback to an older version.</p>
        )}

        {belowFloor && (
          <div className="mt-3 rounded-lg border border-red-300 bg-red-50 px-4 py-3 text-sm">
            <p className="font-semibold text-red-800">Below the rollback floor ({view.rollbackFloor})</p>
            <p className="text-red-800 mt-1">Deploying {target} re-opens fixed security issues on the one internet-facing endpoint:</p>
            {reopened.length > 0 ? (
              <ul className="list-disc ml-5 mt-1 text-red-800 space-y-0.5">
                {reopened.map(r => <li key={r}>{r}</li>)}
              </ul>
            ) : (
              <p className="text-red-700 mt-1 text-xs">Submit once to see exactly what returns, then type the version to confirm.</p>
            )}
            {needsConfirmation && (
              <>
                <label className="block text-xs font-medium text-red-800 mt-3 mb-1">Type {target} to confirm</label>
                <input value={confirmation} onChange={e => setConfirmation(e.target.value)} placeholder={target}
                  className="w-full rounded-lg border border-red-300 px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-red-400" />
              </>
            )}
          </div>
        )}

        {error && !needsConfirmation && <p className="mt-3 text-sm text-red-600">{error}</p>}

        <div className="mt-5 flex justify-end gap-2">
          <button onClick={onClose} className="px-4 py-2 text-sm rounded-lg border border-gray-200 text-gray-700 hover:bg-gray-50">Cancel</button>
          <button onClick={submit}
            disabled={busy || !target || !view.controlDirectoryMounted || target === d.runningVersion || (needsConfirmation && confirmation !== target)}
            className="px-4 py-2 text-sm rounded-lg bg-omnissa text-white font-medium hover:bg-omnissa-dark disabled:opacity-50">
            {busy ? 'Approving…' : belowFloor ? 'Approve rollback' : 'Approve'}
          </button>
        </div>
        <p className="mt-3 text-xs text-gray-400">
          Recorded in the audit trail as <span className="font-mono">update-approved</span> before anything is written for the host.
        </p>
      </div>
    </div>
  )
}

function compareSemver(a: string, b: string): number {
  const pa = a.split('.').map(Number), pb = b.split('.').map(Number)
  for (let i = 0; i < 3; i++) {
    if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pa[i] ?? 0) - (pb[i] ?? 0)
  }
  return 0
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
