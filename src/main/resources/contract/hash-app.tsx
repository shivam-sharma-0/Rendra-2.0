import { HashRouter, Routes, Route, Link, useParams } from 'react-router-dom'
import type { ComponentType } from 'react'

function Page({ component: Component }: { component: ComponentType<{params: Record<string, string>}> }) {
  const values = useParams()
  const params = Object.fromEntries(Object.entries(values).filter((entry): entry is [string, string] => entry[1] !== undefined))
  return <Component params={params} />
}

export default function App() {
  // Canonical home keeps acceptance URLs consistent under the preview's hosting prefix.
  if (!window.location.hash) window.history.replaceState(window.history.state, '', '#/')
  return <HashRouter><Routes>
    {routes.map(route => <Route key={route.path} path={route.path} element={<Page component={route.component} />} />)}
    <Route path="*" element={<main><h1>Page not found</h1><p>The page you requested does not exist.</p><Link to="/">Go to the homepage</Link></main>} />
  </Routes></HashRouter>
}
