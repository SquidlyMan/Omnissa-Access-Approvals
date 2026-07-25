import type { User } from '../types'

/**
 * Role helpers for the SPA (#52). These mirror the backend's authorization
 * rules so the UI only offers controls the signed-in user can actually use.
 *
 * This is a convenience layer, NOT the security boundary — the server still
 * rejects anything the caller is not entitled to. Every call site keeps its
 * error handling for the 403 that arrives anyway.
 */
export type Role = 'ADMIN' | 'APPROVER' | 'VIEWER' | 'AUDITOR'

/** Shown when the server rejects an action with 403. */
export const FORBIDDEN_MESSAGE = 'You do not have permission to do that.'

/**
 * Role names held by the user, ROLE_ prefix stripped. Authorities that are not
 * roles (OIDC_USER, SCOPE_email, …) are ignored, and the legacy ROLE_USER is
 * normalized to VIEWER. A backend older than #52 sends no authorities at all —
 * that yields an empty set rather than an error.
 */
function rolesOf(user: User | null): Set<string> {
  const held = new Set<string>()
  const authorities = user?.authorities
  if (!Array.isArray(authorities)) return held
  for (const authority of authorities) {
    if (typeof authority !== 'string' || !authority.startsWith('ROLE_')) continue
    const role = authority.slice('ROLE_'.length)
    held.add(role === 'USER' ? 'VIEWER' : role)
  }
  return held
}

/** True when the user holds this role. Roles are additive. */
export function hasRole(user: User | null, role: Role): boolean {
  return rolesOf(user).has(role)
}

/** Manage users, rules, tenant config, log bundle, delete requests. */
export function canAdminister(user: User | null): boolean {
  return hasRole(user, 'ADMIN')
}

/** Approve/reject, revoke, revoke-and-block, allow re-request, pull from Access. */
export function canDecide(user: User | null): boolean {
  return hasRole(user, 'ADMIN') || hasRole(user, 'APPROVER')
}

/** See the live queue, request details, dashboard and rules. Auditors cannot. */
export function canViewQueue(user: User | null): boolean {
  return canDecide(user) || hasRole(user, 'VIEWER')
}

/** See the audit trail and its CSV export. Every signed-in role can. */
export function canViewAudit(user: User | null): boolean {
  return canViewQueue(user) || hasRole(user, 'AUDITOR')
}

/** Short label for the highest role held, e.g. "Admin". Empty when unknown. */
export function roleLabel(user: User | null): string {
  const held = rolesOf(user)
  if (held.has('ADMIN')) return 'Admin'
  if (held.has('APPROVER')) return 'Approver'
  if (held.has('AUDITOR')) return 'Auditor'
  if (held.has('VIEWER')) return 'Viewer'
  return ''
}
