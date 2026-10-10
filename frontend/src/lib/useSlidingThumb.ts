import { useEffect, useLayoutEffect, useRef, type RefObject } from 'react'

const SLIDE_MS = 340
const ease = (t: number) => 1 - (1 - t) ** 3
const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

type Box = { x: number; y: number; w: number; h: number }

/**
 * Moves a "thumb" element (the raised or pressed pill behind the current tab) onto the element
 * matching `activeSelector` inside `listRef`, sliding from where it was. While sliding it follows the
 * target's live box frame by frame, so it keeps up as the target's name unfolds and its neighbours
 * shift; at rest it follows later resizes. The list must be the thumb's offset parent.
 */
export function useSlidingThumb(listRef: RefObject<HTMLElement | null>, thumbRef: RefObject<HTMLElement | null>, activeSelector: string) {
  const last = useRef<Box | null>(null)
  const frame = useRef(0)
  const sliding = useRef(false)

  useLayoutEffect(() => {
    const list = listRef.current
    const thumb = thumbRef.current
    const target = list?.querySelector<HTMLElement>(activeSelector)
    if (!list || !thumb || !target) return
    const boxOf = (): Box => ({ x: target.offsetLeft, y: target.offsetTop, w: target.offsetWidth, h: target.offsetHeight })
    const place = (b: Box) => {
      thumb.style.transform = `translate(${b.x}px, ${b.y}px)`
      thumb.style.width = `${b.w}px`
      thumb.style.height = `${b.h}px`
      last.current = b
    }

    const from = last.current
    cancelAnimationFrame(frame.current)
    if (!from || reducedMotion()) {
      sliding.current = false
      place(boxOf())
    } else {
      sliding.current = true
      const start = performance.now()
      const step = (now: number) => {
        const t = Math.min(1, (now - start) / SLIDE_MS)
        const e = ease(t)
        const to = boxOf()
        place({ x: from.x + (to.x - from.x) * e, y: from.y + (to.y - from.y) * e, w: from.w + (to.w - from.w) * e, h: from.h + (to.h - from.h) * e })
        if (t < 1) frame.current = requestAnimationFrame(step)
        else sliding.current = false
      }
      frame.current = requestAnimationFrame(step)
    }

    const observer = new ResizeObserver(() => { if (!sliding.current) place(boxOf()) })
    observer.observe(target)
    observer.observe(list)
    return () => observer.disconnect()
  }, [listRef, thumbRef, activeSelector])

  useEffect(() => () => cancelAnimationFrame(frame.current), [])
}
