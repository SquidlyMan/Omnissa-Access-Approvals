import { useEffect } from 'react'

/**
 * Live queue updates. Pass enabled=false for roles that cannot read the request
 * stream — an auditor gets 403 on /api/approvals/stream, so opening it only
 * produces a failed connection.
 */
export function useSse(onNewRequest: () => void, onQueueUpdate: () => void, enabled = true) {
  useEffect(() => {
    if (!enabled) return
    const es = new EventSource('/api/approvals/stream')
    es.addEventListener('new-request', onNewRequest)
    es.addEventListener('queue-updated', onQueueUpdate)
    return () => es.close()
  }, [onNewRequest, onQueueUpdate, enabled])
}
