import { useCallback, useEffect, useState } from 'react'
import type { UpdateSnapshot } from '../types'
import { getCsrfToken } from '../utils/csrf'

/**
 * Update detection (#83) — the always-on surface.
 *
 * Detection only. Nothing here installs anything; approval and the deploy are
 * separate steps, because the container cannot restart itself without being
 * handed the Docker socket. The banner exists so a newer release is noticed —
 * ZimaOS has no "check for updates" for an externally-managed container, so
 * this is the only place one can appear.
 *
 * Reads the last-known state on load and never hits the registry from a page
 * render; "Check now" is the one action that does, and only an administrator
 * sees it.
 */
export default function UpdateBanner({ isAdmin }: { isAdmin: boolean }) {
  const [snap, setSnap] = useState<UpdateSnapshot | null>(null)
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    fetch('/api/updates/status', { credentials: 'include' })
      .then(r => (r.ok ? r.json() : null))
      .then((s: UpdateSnapshot | null) => { if (s) setSnap(s) })
      .catch(() => { /* the banner is informational; a failed read shows nothing */ })
  }, [])

  useEffect(() => { load() }, [load])

  async function checkNow() {
    setChecking(true)
    setError('')
    try {
      const r = await fetch('/api/updates/check', {
        method: 'POST',
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': getCsrfToken() },
      })
      if (!r.ok) throw new Error(`Server error ${r.status}`)
      setSnap(await r.json())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Check failed')
    } finally {
      setChecking(false)
    }
  }

  if (!snap || !snap.enabled) return null

  const checked = snap.lastCheckedAt ? formatDate(snap.lastCheckedAt) : 'never'

  if (snap.updateAvailable) {
    return (
      <div className="mb-6 rounded-xl border border-amber-300 bg-amber-50 px-5 py-4 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div className="min-w-0">
          <p className="font-semibold text-amber-900">
            Update available — {snap.newestVersion}
            <span className="font-normal text-amber-800"> (running {snap.runningVersion})</span>
          </p>
          <p className="text-xs text-amber-800 mt-0.5">
            Last checked {checked}
            {snap.lastError && <> · last check failed: {snap.lastError}</>}
          </p>
        </div>
        {isAdmin && (
          <div className="shrink-0 flex items-center gap-2">
            <button
              onClick={checkNow}
              disabled={checking}
              className="px-3 py-1.5 text-sm rounded-lg border border-amber-300 bg-white text-amber-900 hover:bg-amber-100 disabled:opacity-50"
            >
              {checking ? 'Checking…' : 'Check now'}
            </button>
          </div>
        )}
        {error && <p className="text-xs text-red-600">{error}</p>}
      </div>
    )
  }

  return (
    <div className="mb-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 text-xs text-gray-400">
      <span>
        {snap.newestVersion
          ? <>You're up to date · {snap.runningVersion} · last checked {checked}</>
          : <>Update check has not completed yet · last checked {checked}</>}
        {snap.lastError && <span className="text-orange-600"> · last check failed: {snap.lastError}</span>}
      </span>
      {isAdmin && (
        <button
          onClick={checkNow}
          disabled={checking}
          className="self-start sm:self-auto text-omnissa hover:underline disabled:opacity-50"
        >
          {checking ? 'Checking…' : 'Check now'}
        </button>
      )}
      {error && <span className="text-red-600">{error}</span>}
    </div>
  )
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
