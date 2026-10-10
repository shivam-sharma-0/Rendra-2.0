import { lazy, Suspense, useEffect } from 'react'
import { Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { WorkspaceProvider } from './workspace/WorkspaceContext'
import RendraApp from './rendra/RendraApp'
import { backgroundFor, useOpenSearch } from './rendra/screens/search/searchRoute'
import { LoadingState } from './components/ui/States'

// Code-split the heavy, rarely-first-viewed routes so the initial (landing/auth) payload stays small.
const ProjectPage = lazy(() => import('./pages/project/ProjectPage'))
const OAuthCallback = lazy(() => import('./rendra/screens/auth/OAuthCallback'))
const Search = lazy(() => import('./rendra/screens/search/Search'))

/** Top-level routes. The Rendra design system renders the bulk of the app under the catch-all. */
export default function App() {
  const location = useLocation()
  const { isAuthenticated } = useAuth()
  const openSearch = useOpenSearch()
  // While the search palette is open, the page it was opened from stays rendered (and inert) behind it.
  const background = backgroundFor(location)

  // ⌘K / Ctrl+K opens search from anywhere once signed in.
  useEffect(() => {
    if (!isAuthenticated) return
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); openSearch() }
    }
    addEventListener('keydown', onKey)
    return () => removeEventListener('keydown', onKey)
  }, [isAuthenticated, openSearch])

  return (
    <WorkspaceProvider>
      <div className="app-page" inert={!!background}>
        <Suspense fallback={<LoadingState label="Loading…" />}>
          <Routes location={background ?? location}>
            <Route path="/project/:id/*" element={<ProjectPage />} />
            <Route path="/oauth/callback" element={<OAuthCallback />} />
            <Route path="*" element={<RendraApp />} />
          </Routes>
        </Suspense>
      </div>
      {background && isAuthenticated && <Suspense fallback={null}><Search /></Suspense>}
    </WorkspaceProvider>
  )
}
