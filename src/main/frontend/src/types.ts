export interface User {
  loginType: 'oauth2' | 'local'
  username: string
  email: string
  name: string
  /**
   * Granted authorities as sent by GET /api/auth/me, e.g.
   * ["OIDC_USER", "ROLE_ADMIN", "SCOPE_email"]. A backend older than #52 omits
   * the field entirely — treat that as no roles (see lib/permissions.ts).
   */
  authorities: string[]
}

/**
 * A local sign-in account as returned by /api/users (#58). Distinct from
 * {@link User}, which describes the current session and may be an OIDC one.
 * Roles carry the ROLE_ prefix, e.g. ["ROLE_ADMIN"].
 */
export interface UserSummary {
  id: number
  username: string
  firstName: string
  lastName: string
  email: string
  enabled: boolean
  roles: string[]
}

export interface CalloutRequest {
  id: number
  requestId: string
  resourceName: string
  resourceUuid: string
  userId: string
  userDeviceName: string
  state: string
  operation: string
  receivedDate: string
  responseDate: string | null
  responseMessage: string | null
  notes: string | null
  userAttributes: Record<string, string[]>
  // JIT / time-bound access (#49). Null on permanent grants.
  accessTtlMinutes: number | null
  accessExpiresAt: string | null
  revokedAt: string | null
  reRequestable: boolean | null
  assignmentType: string | null
  activationPolicy: string | null
  restoreAt: string | null
  restoredAt: string | null
  // Multi-stage approval chains (#53). Null = not chained.
  chainId?: number | null
  currentStage?: number | null
  // Delegation and escalation (#51). assignedOwner is advisory — it never
  // decides who may act, only who the queue shows as holding the request.
  assignedOwner?: string | null
  assignedAt?: string | null
  escalatedAt?: string | null
  escalationStage?: number | null
}

/** An entry in the assign picker, resolved live from Omnissa Access. */
export interface Approver {
  identity: string
  displayName: string
  email: string
}

/**
 * Every paged endpoint's response body — the mirror of the backend's
 * PagedResponse DTO (#64). One declaration for all of them: the audit trail
 * used to carry its own near-identical copy, so the two drifted (only one knew
 * about `first`/`last`) and a backend field change had two places to be missed.
 *
 * `pageable` and `sort` are also on the wire but are Spring Data's internals
 * and nothing here reads them, so they are left undeclared rather than
 * invited into the UI.
 */
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  /** Zero-based — the UI shows `number + 1`. */
  number: number
  size: number
  numberOfElements: number
  first: boolean
  last: boolean
  empty: boolean
}

export interface Stats {
  pending: number
  approved: number
  rejected: number
  deactivated: number
}

export type AuditAction =
  | 'request-received'
  | 'deactivation-received'
  | 'approved'
  | 'rejected'
  | 'auto-approved'
  | 'auto-rejected'
  | 'decision-undeliverable'
  | 'access-revoked'
  | 'access-reopened'
  | 'request-deleted'
  | 'access-blocked'
  | 'access-block-failed'
  // Multi-stage approval chains (#53)
  | 'chain-matched'
  | 'stage-approved'
  // Delegation and escalation (#51)
  | 'request-claimed'
  | 'request-released'
  | 'request-escalated'

export interface AuditEvent {
  id: number
  timestamp: string
  adminUsername: string
  action: AuditAction
  requestId: string
  resourceName: string
  /** Who the access was for — distinct from adminUsername, who acted. */
  requesterId: string | null
  requesterName: string | null
  requesterEmail: string | null
  message: string
}

export interface Rule {
  id: number
  enabled: boolean
  action: 'approve' | 'reject'
  appPattern: string | null
  groupName: string | null
  expiryDays: number | null
  grantTtlMinutes: number | null
  /** Escalation (#51) rides on the expiry rule: "nudge at 4h, reject at 3d". */
  escalateAfterMinutes?: number | null
  /** Auto-release an unactioned claim; null inherits escalateAfterMinutes. */
  claimTtlMinutes?: number | null
}

/**
 * A multi-stage approval chain definition (#53). Requests matching
 * appPattern/groupName route through this chain's stages, sequentially,
 * instead of the ordinary single-decision flow.
 */
export interface ApprovalChain {
  id: number
  enabled: boolean
  name: string
  appPattern: string | null
  groupName: string | null
}

/**
 * One ordered stage of an {@link ApprovalChain}. approverValue is a
 * ROLE_* authority name when approverType is 'ROLE', or an Access group id
 * when it's 'GROUP'.
 */
export interface ApprovalStage {
  id: number
  chainId: number
  stageOrder: number
  approverType: 'ROLE' | 'GROUP' | 'USER'
  approverValue: string
}

export interface TenantStatus {
  version?: string
  tenantUrl: string
  reachable: boolean
  checkedAt: string
  error: string | null
}
