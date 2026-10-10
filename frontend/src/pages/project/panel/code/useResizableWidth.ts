import { useCallback, useRef, useState, type PointerEvent, type RefObject } from 'react'
import { clamp } from '../../layout'

/**
 * A width the user drags with a handle, remembered in localStorage under `storageKey`. Kept between
 * `min` and the container's width minus `reserve` (room left for the content beside it). Double
 * click on the handle goes back to `initial`.
 */
export function useResizableWidth(containerRef: RefObject<HTMLElement | null>, storageKey: string, { initial, min, reserve }: { initial: number; min: number; reserve: number }) {
  const [width, setWidth] = useState(() => {
    try {
      const saved = Number(localStorage.getItem(storageKey))
      return Number.isFinite(saved) && saved >= min ? saved : initial
    } catch { return initial }
  })
  const [dragging, setDragging] = useState(false)
  const latest = useRef(width)

  const commit = useCallback((value: number) => {
    latest.current = value
    setWidth(value)
    try { localStorage.setItem(storageKey, String(Math.round(value))) } catch { /* storage unavailable */ }
  }, [storageKey])

  const onPointerDown = useCallback((e: PointerEvent<HTMLElement>) => {
    e.preventDefault()
    setDragging(true)
    e.currentTarget.setPointerCapture(e.pointerId)
  }, [])
  const onPointerMove = useCallback((e: PointerEvent<HTMLElement>) => {
    if (!e.buttons) return
    const rect = containerRef.current?.getBoundingClientRect()
    if (!rect) return
    latest.current = clamp(e.clientX - rect.left, min, Math.max(min, rect.width - reserve))
    setWidth(latest.current)
  }, [containerRef, min, reserve])
  const onPointerUp = useCallback(() => {
    setDragging(false)
    commit(latest.current)
  }, [commit])
  const onDoubleClick = useCallback(() => commit(initial), [commit, initial])

  return { width, dragging, handle: { onPointerDown, onPointerMove, onPointerUp, onPointerCancel: onPointerUp, onDoubleClick } }
}
