import { useState } from 'react'

interface Props {
  resourceUuid: string
  resourceName: string
  size?: number
}

export default function AppIcon({ resourceUuid, resourceName, size = 40 }: Props) {
  const [errored, setErrored] = useState(false)
  const initials = resourceName.slice(0, 2).toUpperCase()

  if (errored || !resourceUuid) {
    return (
      <div
        style={{ width: size, height: size }}
        className="flex items-center justify-center rounded-lg bg-omnissa-light text-omnissa font-bold text-sm select-none shrink-0"
      >
        {initials}
      </div>
    )
  }

  return (
    <img
      src={`/api/catalogitems/${resourceUuid}/icon`}
      alt={resourceName}
      width={size}
      height={size}
      className="rounded-lg object-cover shrink-0"
      onError={() => setErrored(true)}
    />
  )
}
