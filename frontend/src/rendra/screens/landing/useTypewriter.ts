import { useEffect, useState } from 'react'

const TICK_MS = 55
const HOLD_TYPED_TICKS = 34 // pause on a fully typed phrase
const HOLD_ERASED_TICKS = 6 // pause before typing the next phrase
const ERASE_STEP = 2 // erase faster than typing

const prefersReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

/**
 * Types each phrase out, holds, erases it, and moves to the next, forever.
 * With reduced motion the first phrase is shown whole and nothing animates.
 */
export function useTypewriter(phrases: readonly string[]): string {
  const [text, setText] = useState(() => (prefersReducedMotion() ? phrases[0] ?? '' : ''))

  useEffect(() => {
    if (prefersReducedMotion() || phrases.length === 0) return
    const s = { phrase: 0, length: 0, erasing: false, hold: 0 }

    const timer = window.setInterval(() => {
      if (s.hold > 0) { s.hold -= 1; return }
      const current = phrases[s.phrase]
      if (!s.erasing) {
        s.length += 1
        if (s.length >= current.length) { s.erasing = true; s.hold = HOLD_TYPED_TICKS }
      } else {
        s.length = Math.max(0, s.length - ERASE_STEP)
        if (s.length === 0) { s.erasing = false; s.phrase = (s.phrase + 1) % phrases.length; s.hold = HOLD_ERASED_TICKS }
      }
      setText(current.slice(0, s.length))
    }, TICK_MS)

    return () => window.clearInterval(timer)
  }, [phrases])

  return text
}
