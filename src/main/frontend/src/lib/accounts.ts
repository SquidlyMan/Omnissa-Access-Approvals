import { getCsrfToken } from '../utils/csrf'
import { FORBIDDEN_MESSAGE } from './permissions'
import type { Role } from './permissions'

/**
 * Helpers for local account management (#58).
 *
 * Local sign-in is the break-glass route into this tool: roles normally come
 * from Omnissa Access group membership, so an enabled local admin is the only
 * way back in when the tenant is unreachable or the role map is wrong. The
 * server therefore refuses changes that would remove the last one. This module
 * keeps that distinction visible to the UI — see {@link AccountError}.
 */

/** Mirrors PasswordPolicy.MIN_LENGTH — the server rejects anything shorter. */
export const MIN_PASSWORD_LENGTH = 12

export const PASSWORD_RULE = `At least ${MIN_PASSWORD_LENGTH} characters.`

/**
 * The rest of PasswordPolicy, stated up front so the server's rejection is not
 * the first the user hears of it. Only the length is checked here — the other
 * rules stay server-side and arrive as a 400 carrying their own explanation.
 */
export const PASSWORD_GUIDANCE =
  'A passphrase of a few unrelated words is ideal — there is no uppercase, digit or symbol '
  + 'requirement. It must not repeat one character, contain the username, or be a well-known password.'

/** The roles PUT /api/users/{id}/roles accepts, in descending privilege order. */
export const ROLE_OPTIONS: { role: Role; label: string; description: string }[] = [
  { role: 'ADMIN', label: 'Admin', description: 'Manage accounts, rules and configuration; approve and revoke.' },
  { role: 'APPROVER', label: 'Approver', description: 'Approve, reject and revoke access.' },
  { role: 'VIEWER', label: 'Viewer', description: 'Read the dashboard, queue and rules.' },
  { role: 'AUDITOR', label: 'Auditor', description: 'Read and export the audit trail only.' },
]

/** "ROLE_ADMIN" → "Admin". Anything unrecognized is shown as sent. */
export function roleLabelOf(authority: string): string {
  const name = authority.startsWith('ROLE_') ? authority.slice('ROLE_'.length) : authority
  const known = ROLE_OPTIONS.find(o => o.role === name)
  if (known) return known.label
  // The backend still recognizes the legacy ROLE_USER as a viewer.
  if (name === 'USER') return 'Viewer'
  return name
}

/** Role names an account holds, ROLE_ prefix stripped, for the edit dialog. */
export function rolesOfAccount(roles: string[]): Set<string> {
  const held = new Set<string>()
  for (const authority of roles) {
    const name = authority.startsWith('ROLE_') ? authority.slice('ROLE_'.length) : authority
    held.add(name === 'USER' ? 'VIEWER' : name)
  }
  return held
}

export interface AccountError {
  message: string
  /**
   * True when the server refused the change deliberately — 409 (this would
   * remove the last enabled local admin, or the username is taken) and 400 (an
   * unknown role). Those responses explain a rule rather than report a fault,
   * so they are shown prominently and in the server's own words instead of as
   * a generic failure.
   */
  refused: boolean
}

/**
 * Turns a failed response into something worth showing. Prefers the server's
 * own `error` string wherever it sends one; the UI never paraphrases a rule it
 * does not enforce itself.
 */
export async function readAccountError(res: Response): Promise<AccountError> {
  const body: { error?: string } | null = await res.json().catch(() => null)
  const serverMessage = typeof body?.error === 'string' && body.error ? body.error : ''

  if (res.status === 409 || res.status === 400) {
    return { message: serverMessage || `Server error ${res.status}`, refused: true }
  }
  if (res.status === 401) {
    return { message: 'Your session has ended. Sign in again.', refused: false }
  }
  if (res.status === 403) {
    // PUT /me/password answers 403 with its own wording when the current
    // password is wrong — that is not a permissions problem, so it wins.
    return { message: serverMessage || FORBIDDEN_MESSAGE, refused: false }
  }
  return { message: serverMessage || `Server error ${res.status}`, refused: false }
}

/** Every mutating account call: session cookie plus the CSRF header. */
export function accountRequest(url: string, method: string, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = { 'X-XSRF-TOKEN': getCsrfToken() }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  return fetch(url, {
    method,
    credentials: 'include',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}
