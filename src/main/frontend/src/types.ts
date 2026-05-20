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
