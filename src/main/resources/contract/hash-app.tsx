import { HashRouter, Routes, Route, Link, NavLink, useParams } from 'react-router-dom'
import type { ComponentType } from 'react'

function Page({ component: Component }: { component: ComponentType<{params: Record<string, string>}> }) {
  const values = useParams()
  const params = Object.fromEntries(Object.entries(values).filter((entry): entry is [string, string] => entry[1] !== undefined))
  return <Component params={params} />
}

const navigation = routes.filter(route => !route.hidden && !route.path.includes(':')
  && !/(^|\/)(auth|log-?in|sign-?in|sign-?up|register)(\/|$)/i.test(route.path))

function label(path: string, full = false): string {
  const parts = path.split('/').filter(Boolean)
  const text = (full ? parts.join(' ') : parts.pop() ?? '').replace(/[-_]+/g, ' ')
  return text ? text.replace(/^./, first => first.toUpperCase()) : 'Home'
}

const labelCounts = navigation.reduce((counts, route) => counts.set(label(route.path), (counts.get(label(route.path)) ?? 0) + 1),
  new Map<string, number>())

function navLabel(path: string): string {
  return (labelCounts.get(label(path)) ?? 0) > 1 ? label(path, true) : label(path)
}

export default function App() {
  if (!window.location.hash) window.history.replaceState(window.history.state, '', '#/')
  return <HashRouter>
    <header className="app-header">
      <div className="app-container app-header__bar">
        <Link to="/" className="app-brand">{APP_MARK && <span className="app-brand__mark" aria-hidden="true">{APP_MARK}</span>}{APP_NAME}</Link>
        {navigation.length > 1 && <nav className="app-nav" aria-label="Main">
          {navigation.slice(0, navigation.length > 6 ? 5 : 6).map(route =>
            <NavLink key={route.path} to={route.path} end={route.path === '/'}>{navLabel(route.path)}</NavLink>)}
          {navigation.length > 6 && <details className="app-nav__more">
            <summary>More</summary>
            <div className="app-nav__menu">
              {navigation.slice(5).map(route => <NavLink key={route.path} to={route.path}>{navLabel(route.path)}</NavLink>)}
            </div>
          </details>}
        </nav>}
        <AccountMenu />
      </div>
    </header>
    <div className="app-container app-main">
      <Routes>
        {routes.map(route => <Route key={route.path} path={route.path} element={<Page component={route.component} />} />)}
        <Route path="*" element={<section className="app-card app-stack"><h1>Page not found</h1><p className="app-muted">The page you requested does not exist.</p><Link to="/">Go to the homepage</Link></section>} />
      </Routes>
    </div>
    <Toaster />
  </HashRouter>
}
