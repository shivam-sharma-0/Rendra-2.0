import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useMediaQuery } from './useMediaQuery'

/** At or below this width the sidebar is an overlay drawer instead of a docked column. */
export const COMPACT_QUERY = '(max-width: 1024px)'
const COLLAPSED_KEY = 'rendra_sidebar_collapsed'

function readCollapsed(): boolean {
  try { return localStorage.getItem(COLLAPSED_KEY) === 'true' } catch { return false }
}

/**
 * Sidebar layout state.
 * - Wide screens: docked, either expanded or collapsed to an icon rail (remembered).
 * - Compact screens: hidden until opened as a drawer; it closes itself on navigation
 *   because it is tied to the path it was opened on.
 * Ctrl/⌘+B toggles whichever applies.
 */
export function useSidebarState() {
  const { pathname } = useLocation()
  const compact = useMediaQuery(COMPACT_QUERY)
  const [collapsedPref, setCollapsedPref] = useState(readCollapsed)
  const [drawerPath, setDrawerPath] = useState<string | null>(null)
  const drawerOpen = compact && drawerPath === pathname

  const setCollapsed = (next: boolean) => {
    setCollapsedPref(next)
    try { localStorage.setItem(COLLAPSED_KEY, String(next)) } catch { /* not remembered */ }
  }
  const toggleCollapsed = () => setCollapsed(!collapsedPref)
  const openDrawer = () => setDrawerPath(pathname)
  const closeDrawer = () => setDrawerPath(null)
  const toggle = () => (compact ? (drawerOpen ? closeDrawer() : openDrawer()) : toggleCollapsed())

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

  return { compact, collapsed: !compact && collapsedPref, toggleCollapsed, drawerOpen, openDrawer, closeDrawer }
}
