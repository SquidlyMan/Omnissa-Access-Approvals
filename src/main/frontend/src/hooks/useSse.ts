import { useEffect } from 'react'

export function useSse(onNewRequest: () => void, onQueueUpdate: () => void) {
  useEffect(() => {
    const es = new EventSource('/api/approvals/stream')
    es.addEventListener('new-request', onNewRequest)
    es.addEventListener('queue-updated', onQueueUpdate)
    return () => es.close()
  }, [onNewRequest, onQueueUpdate])
}
