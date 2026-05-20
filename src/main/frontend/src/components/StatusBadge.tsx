const styles: Record<string, string> = {
  pending:     'bg-amber-100 text-amber-800',
  approved:    'bg-green-100 text-green-800',
  rejected:    'bg-red-100 text-red-800',
  deactivated: 'bg-gray-100 text-gray-600',
}

const labels: Record<string, string> = {
  pending:     'Awaiting Review',
  approved:    'Approved',
  rejected:    'Rejected',
  deactivated: 'Deactivated',
}

export default function StatusBadge({ state }: { state: string }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${styles[state] ?? 'bg-gray-100 text-gray-600'}`}>
      {labels[state] ?? state}
    </span>
  )
}
