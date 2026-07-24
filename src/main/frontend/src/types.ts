export interface User {
  loginType: 'oauth2' | 'local'
  username: string
  email: string
  name: string
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
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
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
  | 'request-deleted'

export interface AuditEvent {
  id: number
  timestamp: string
  adminUsername: string
  action: AuditAction
  requestId: string
  resourceName: string
  message: string
}

export interface AuditPage {
  content: AuditEvent[]
  totalElements: number
  totalPages: number
  number: number
  last: boolean
  first: boolean
}

export interface Rule {
  id: number
  enabled: boolean
  action: 'approve' | 'reject'
  appPattern: string | null
  groupName: string | null
  expiryDays: number | null
  grantTtlMinutes: number | null
}

export interface TenantStatus {
  version?: string
  tenantUrl: string
  reachable: boolean
  checkedAt: string
  error: string | null
}
