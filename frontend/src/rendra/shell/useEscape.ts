import { useEffect, useRef } from 'react'

/** Calls `onEscape` when Escape is pressed, while `enabled`. */
export function useEscape(onEscape: () => void, enabled = true) {
  const handler = useRef(onEscape)
  useEffect(() => { handler.current = onEscape })

  useEffect(() => {
    if (!enabled) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') handler.current() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [enabled])
}
