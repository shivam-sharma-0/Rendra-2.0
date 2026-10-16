import { useSyncExternalStore } from 'react'

function routePath(): string {
  const hash = window.location.hash
  return hash.startsWith('#/') ? hash.slice(1) : '/'
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
  const Component = route?.component
  return Component ? <Component /> : <main><h1>Page not found</h1>
    <p>The page you requested does not exist.</p><a href="#/">Go to the homepage</a></main>
}
