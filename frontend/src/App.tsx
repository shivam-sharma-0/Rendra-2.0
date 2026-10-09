import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router-dom'
import { WorkspaceProvider } from './workspace/WorkspaceContext'
import RendraApp from './rendra/RendraApp'
import { LoadingState } from './components/ui/States'

// Code-split the heavy, rarely-first-viewed routes so the initial (landing/auth) payload stays small.
const ProjectPage = lazy(() => import('./pages/project/ProjectPage'))
const OAuthCallback = lazy(() => import('./pages/OAuthCallback'))

/** Top-level routes. The Rendra design system renders the bulk of the app under the catch-all. */
export default function App() {
  return (
    <WorkspaceProvider>
      <Suspense fallback={<LoadingState label="Loading…" />}>
        <Routes>
          <Route path="/project/:id/*" element={<ProjectPage />} />
          <Route path="/oauth/callback" element={<OAuthCallback />} />
          <Route path="*" element={<RendraApp />} />
        </Routes>
      </Suspense>
    </WorkspaceProvider>
  )
}
