import { useEffect, useState } from 'react'

/** Re-renders every `intervalMs` (default one second) while active, so live elapsed-time labels stay current. */
export function useTicker(active: boolean, intervalMs = 1000) {
  const [, setTick] = useState(0)
  useEffect(() => {
    if (!active) return
    const id = setInterval(() => setTick((t) => t + 1), intervalMs)
    return () => clearInterval(id)
  }, [active, intervalMs])
}
