import { useState, type MouseEvent as ReactMouseEvent } from 'react'

const COLLAPSED_WIDTH = 42
const COLLAPSE_BELOW = 120
const MIN_EXPANDED = 150
const DEFAULT_SHARE = 0.14 // of the window width
const MAX_SHARE = 0.15

/**
 * Width of the drag-resizable sidebar. Dragging below COLLAPSE_BELOW snaps it to the
 * collapsed rail; dragging back out snaps to at least MIN_EXPANDED.
 */
export function useSidebarWidth() {
  const [width, setWidth] = useState(() => Math.round(window.innerWidth * DEFAULT_SHARE))
  const [isResizing, setIsResizing] = useState(false)

  const startResize = (e: ReactMouseEvent) => {
    e.preventDefault()
    setIsResizing(true)
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    const startX = e.clientX
    const startWidth = width

    const onMove = (me: MouseEvent) => {
      let next = Math.min(startWidth + (me.clientX - startX), Math.round(window.innerWidth * MAX_SHARE))
      if (next < COLLAPSE_BELOW) next = COLLAPSED_WIDTH
      if (next > COLLAPSED_WIDTH && next < MIN_EXPANDED) next = MIN_EXPANDED
      setWidth(next)
    }
    const onUp = () => {
      setIsResizing(false)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  return { width, isResizing, collapsed: width < COLLAPSE_BELOW, startResize }
}
