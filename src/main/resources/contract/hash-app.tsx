import { useSyncExternalStore } from 'react'

function routePath(): string {
  const hash = window.location.hash
  return hash.startsWith('#/') ? hash.slice(1).split('?')[0] : '/'
}

function matchRoute(pattern: string, path: string): Record<string, string> | null {
  const expected = pattern.split('/').filter(Boolean)
  const actual = path.split('/').filter(Boolean)
  if (expected.length !== actual.length) return null
  const params: [string, string][] = []
  for (let index = 0; index < expected.length; index++) {
    if (expected[index].startsWith(':')) {
      try { params.push([expected[index].slice(1), decodeURIComponent(actual[index])]) }
      catch { return null }
    } else if (expected[index] !== actual[index]) return null
  }
  return Object.fromEntries(params)
}

function subscribe(notify: () => void): () => void {
  const update = () => {
    // Canonical home URLs keep route checks and browser back correct under a hosting prefix.
    if (!window.location.hash) window.history.replaceState(window.history.state, '', '#/')
    notify()
  }
  window.addEventListener('hashchange', update)
  update()
  return () => window.removeEventListener('hashchange', update)
}

export default function App() {
  const path = useSyncExternalStore(subscribe, routePath, routePath)
  const route = routes.find(route => route.path === path)
    ?? routes.find(route => matchRoute(route.path, path) !== null)
  const Component = route?.component
  const params = route ? matchRoute(route.path, path) ?? {} : {}
  return Component ? <Component params={params} /> : <main><h1>Page not found</h1>
    <p>The page you requested does not exist.</p><a href="#/">Go to the homepage</a></main>
}
