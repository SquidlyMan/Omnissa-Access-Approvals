import type { CalloutRequest } from '../types'

/**
 * Human-readable label for who made a request. Access sends a numeric userId
 * plus a userAttributes map (firstName / lastName / email / userName). Prefer a
 * real name or email; fall back to the numeric userId only when nothing else is
 * present.
 */
export function requesterLabel(req: Pick<CalloutRequest, 'userId' | 'userAttributes'>): string {
  const attr = req.userAttributes ?? {}
  const first = attr['firstName']?.[0] ?? ''
  const last = attr['lastName']?.[0] ?? ''
  const name = `${first} ${last}`.trim()
  if (name) return name
  return attr['userName']?.[0] ?? attr['email']?.[0] ?? req.userId
}
