import { HashRouter, Routes, Route, Link, useParams } from 'react-router-dom'
import type { ComponentType } from 'react'

function Page({ component: Component }: { component: ComponentType<{params: Record<string, string>}> }) {
  const values = useParams()
  const params = Object.fromEntries(Object.entries(values).filter((entry): entry is [string, string] => entry[1] !== undefined))
  return <Component params={params} />
}

// Pages without parameters, except sign-in and sign-up, which their pages link to themselves.
const navigation = routes.filter(route => !route.path.includes(':')
  && !/(^|\/)(auth|log-?in|sign-?in|sign-?up|register)(\/|$)/i.test(route.path))

function label(path: string): string {
  const last = path.split('/').filter(Boolean).pop()
  return last ? last.replace(/[-_]+/g, ' ').replace(/^./, first => first.toUpperCase()) : 'Home'
}

export default function App() {
  // Canonical home keeps acceptance URLs consistent under the preview's hosting prefix.
  if (!window.location.hash) window.history.replaceState(window.history.state, '', '#/')
  return <HashRouter>
    <header className="app-header">
      <div className="app-container app-header__bar">
        <Link to="/" className="app-brand">{APP_NAME}</Link>
        {navigation.length > 1 && <nav className="app-nav" aria-label="Main">
          {navigation.map(route => <Link key={route.path} to={route.path}>{label(route.path)}</Link>)}
        </nav>}
      </div>
    </header>
    {/* Pages render their own main landmark. */}
    <div className="app-container app-main">
      <Routes>
        {routes.map(route => <Route key={route.path} path={route.path} element={<Page component={route.component} />} />)}
        <Route path="*" element={<section className="app-card app-stack"><h1>Page not found</h1><p className="app-muted">The page you requested does not exist.</p><Link to="/">Go to the homepage</Link></section>} />
      </Routes>
    </div>
  </HashRouter>
}
