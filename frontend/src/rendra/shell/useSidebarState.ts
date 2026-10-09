import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent } from 'react'
import { useLocation } from 'react-router-dom'
import { useMediaQuery } from './useMediaQuery'

/** At or below this width the sidebar is an overlay drawer instead of a docked column. */
export const COMPACT_QUERY = '(max-width: 1024px)'

const COLLAPSED_KEY = 'rendra_sidebar_collapsed'
const WIDTH_KEY = 'rendra_sidebar_width'
export const SIDEBAR_WIDTH = { default: 240, min: 200, max: 360 } as const
/** Width of the collapsed icon rail; keep in sync with `.kap-side.collapsed` in index.css. */
export const RAIL_WIDTH = 44
/** Dragging the edge narrower than this collapses the sidebar to its icon rail. */
const COLLAPSE_BELOW = 150
const KEY_STEP = 16

function read<T>(key: string, parse: (raw: string | null) => T): T {
  try { return parse(localStorage.getItem(key)) } catch { return parse(null) }
}
function write(key: string, value: string) {
  try { localStorage.setItem(key, value) } catch { /* not remembered */ }
}
const clampWidth = (w: number) => Math.min(SIDEBAR_WIDTH.max, Math.max(SIDEBAR_WIDTH.min, Math.round(w)))
const parseWidth = (raw: string | null) => (raw && Number.isFinite(Number(raw)) ? clampWidth(Number(raw)) : SIDEBAR_WIDTH.default)

/**
 * Sidebar layout state.
 * - Wide screens: docked and drag-resizable between min and max width; dragging narrower
 *   collapses it to an icon rail, dragging the rail outwards expands it. Width and collapsed
 *   state are remembered.
 * - Compact screens: hidden until opened as a drawer; it closes itself on navigation
 *   because it is tied to the path it was opened on.
 * Ctrl/⌘+B toggles whichever applies.
 */
export function useSidebarState() {
  const { pathname } = useLocation()
  const compact = useMediaQuery(COMPACT_QUERY)
  const [collapsedPref, setCollapsedPref] = useState(() => read(COLLAPSED_KEY, (raw) => raw === 'true'))
  const [width, setWidth] = useState(() => read(WIDTH_KEY, parseWidth))
  const [resizing, setResizing] = useState(false)
  const [drawerPath, setDrawerPath] = useState<string | null>(null)
  const drawerOpen = compact && drawerPath === pathname

  const setCollapsed = (next: boolean) => {
    setCollapsedPref(next)
    write(COLLAPSED_KEY, String(next))
  }
  const commitWidth = (next: number) => {
    const w = clampWidth(next)
    setWidth(w)
    write(WIDTH_KEY, String(w))
  }
  const toggleCollapsed = () => setCollapsed(!collapsedPref)
  const openDrawer = () => setDrawerPath(pathname)
  const closeDrawer = () => setDrawerPath(null)
  const toggle = () => (compact ? (drawerOpen ? closeDrawer() : openDrawer()) : toggleCollapsed())

  /** Pointer drag on the sidebar's edge. */
  const startResize = (e: ReactMouseEvent) => {
    e.preventDefault()
    const startX = e.clientX
    const startWidth = collapsedPref ? RAIL_WIDTH : width
    let latest = width
    let collapsedNow = collapsedPref
    setResizing(true)
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'

    const onMove = (me: MouseEvent) => {
      const raw = startWidth + (me.clientX - startX)
      if (raw < COLLAPSE_BELOW) {
        if (!collapsedNow) { collapsedNow = true; setCollapsedPref(true) }
        return
      }
      if (collapsedNow) { collapsedNow = false; setCollapsedPref(false) }
      latest = clampWidth(raw)
      setWidth(latest)
    }
    const onUp = () => {
      setResizing(false)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      write(COLLAPSED_KEY, String(collapsedNow))
      if (!collapsedNow) write(WIDTH_KEY, String(latest))
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  /** Keyboard on the focused edge: arrows resize, Home/End jump to min/max, Enter toggles. */
  const onResizeKey = (e: ReactKeyboardEvent) => {
    const step = { ArrowLeft: -KEY_STEP, ArrowRight: KEY_STEP }[e.key]
    if (step) {
      e.preventDefault()
      if (collapsedPref) { if (step > 0) setCollapsed(false); return }
      if (step < 0 && width <= SIDEBAR_WIDTH.min) { setCollapsed(true); return }
      commitWidth(width + step)
    } else if (e.key === 'Home' || e.key === 'End') {
      e.preventDefault()
      setCollapsed(false)
      commitWidth(e.key === 'Home' ? SIDEBAR_WIDTH.min : SIDEBAR_WIDTH.max)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      toggleCollapsed()
    }
  }

  const resetWidth = () => { setCollapsed(false); commitWidth(SIDEBAR_WIDTH.default) }

  const toggleRef = useRef(toggle)
  useEffect(() => { toggleRef.current = toggle })
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey && e.key.toLowerCase() === 'b') {
        e.preventDefault()
        toggleRef.current()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return {
    compact,
    collapsed: !compact && collapsedPref,
    width,
    resizing,
    toggleCollapsed,
    startResize,
    onResizeKey,
    resetWidth,
    drawerOpen,
    openDrawer,
    closeDrawer,
  }
}
