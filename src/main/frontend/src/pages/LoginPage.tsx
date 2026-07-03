import { useState, useEffect } from 'react'
import { getCsrfToken } from '../utils/csrf'

const LOGIN_FAILED_MESSAGE =
  "Invalid username or password. Local admin credentials are defined in the container's environment values (OMNISSA_BOOTSTRAP_ADMIN_USERNAME / OMNISSA_BOOTSTRAP_ADMIN_PASSWORD)."

interface AuthConfig {
  localLoginDisabled: boolean
  oauthEnabled: boolean
}

export default function LoginPage() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [authConfig, setAuthConfig] = useState<AuthConfig>({ localLoginDisabled: false, oauthEnabled: true })

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    if (params.get('error')) setError(LOGIN_FAILED_MESSAGE)
    if (params.get('logout')) setError('')
  }, [])

  useEffect(() => {
    fetch('/api/config/auth', { credentials: 'include' })
      .then(r => r.ok ? r.json() : null)
      .then((cfg: AuthConfig | null) => {
        if (cfg) {
          setAuthConfig({
            localLoginDisabled: cfg.localLoginDisabled === true,
            oauthEnabled: cfg.oauthEnabled !== false,
          })
        }
      })
      .catch(() => { /* default: show everything */ })
  }, [])

  async function handleLocalLogin(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError('')
    const form = new URLSearchParams()
    form.append('username', username)
    form.append('password', password)
    const res = await fetch('/login/local', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': getCsrfToken() },
      body: form.toString(),
    })
    if (res.ok || res.redirected) {
      window.location.href = '/dashboard'
    } else {
      setError(LOGIN_FAILED_MESSAGE)
      setSubmitting(false)
    }
  }

  const showLocalLogin = !authConfig.localLoginDisabled
  const showOauth = authConfig.oauthEnabled
  const showDivider = showLocalLogin && showOauth

  return (
    <div className="min-h-screen bg-omnissa-light flex flex-col items-center justify-center px-4">
      <div className="bg-white rounded-2xl shadow-lg w-full max-w-sm p-8">
        {/* Logo / header */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-full bg-omnissa-light mb-3">
            <svg className="w-8 h-8 text-omnissa" fill="currentColor" viewBox="0 0 24 24">
              <path d="M12 1L3 5v6c0 5.25 3.75 10.15 9 11.35C17.25 21.15 21 16.25 21 11V5l-9-4z"/>
            </svg>
          </div>
          <h1 className="text-xl font-semibold text-gray-900">Omnissa Access Approval Tool</h1>
          <p className="text-sm text-gray-500 mt-1">Sign in to manage access requests</p>
        </div>

        {/* OAuth2 sign-in */}
        {showOauth && (
          <a
            href="/oauth2/authorization/omnissa"
            className="flex items-center justify-center gap-2 w-full rounded-lg bg-omnissa text-white py-2.5 text-sm font-medium hover:bg-omnissa-dark transition-colors mb-6"
          >
            Sign in with Omnissa Access
          </a>
        )}

        {showDivider && (
          <div className="relative mb-6">
            <div className="absolute inset-0 flex items-center"><div className="w-full border-t border-gray-200" /></div>
            <div className="relative flex justify-center text-xs text-gray-400 bg-white px-2">or</div>
          </div>
        )}

        {/* Local username/password */}
        {showLocalLogin && (
          <form onSubmit={handleLocalLogin} className="space-y-3">
            {error && (
              <div className="rounded-lg bg-red-50 border border-red-200 px-3 py-2 text-sm text-red-700">
                {error}
              </div>
            )}
            <input
              type="text"
              placeholder="Username"
              value={username}
              onChange={e => setUsername(e.target.value)}
              required
              className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-omnissa"
            />
            <input
              type="password"
              placeholder="Password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
              className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-omnissa"
            />
            <button
              type="submit"
              disabled={submitting}
              className="w-full rounded-lg border border-gray-300 py-2.5 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 transition-colors"
            >
              {submitting ? 'Signing in…' : 'Sign in with password'}
            </button>
          </form>
        )}
      </div>

      <p className="w-full max-w-sm text-red-600 text-xs text-center mt-4">
        LEGAL &amp; NON-PRODUCTION DISCLAIMER: This tool is provided as-is, without warranty of any
        kind, for testing and demonstration of Omnissa Access application approvals only. It is not
        an official Omnissa product and is not supported by Omnissa. Do not use in production or
        with production data.
      </p>
    </div>
  )
}
