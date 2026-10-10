import { useCallback } from 'react'
import { useLocation, useNavigate, type Location } from 'react-router-dom'

/**
 * Search is a palette over the page it was opened from: `/search` keeps that page in the history
 * state (`background`) and the app keeps rendering it behind the palette.
 */
export const SEARCH_PATH = '/search'
type SearchState = { background?: Location }

/** Opens the search palette over the current page. */
export function useOpenSearch() {
  const nav = useNavigate()
  const location = useLocation()
  return useCallback(() => {
    if (location.pathname === SEARCH_PATH) return
    const state: SearchState = { background: location }
    nav(SEARCH_PATH, { state })
  }, [nav, location])
}

/** True when the palette was opened over a page (Back returns there); false when /search was opened directly. */
export const hasBackground = (location: Location) => !!(location.state as SearchState | null)?.background

/** The page to render behind the palette — where it was opened from, or the dashboard — or null when search is closed. */
export function backgroundFor(location: Location): Location | null {
  if (location.pathname !== SEARCH_PATH) return null
  return (location.state as SearchState | null)?.background ?? { ...location, pathname: '/dashboard', search: '', hash: '', state: null }
}
