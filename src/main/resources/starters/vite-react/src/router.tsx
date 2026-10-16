import { Children, isValidElement, useEffect, useState, type ReactNode } from 'react'

// A small hash router so an app can have several pages without another package.
// Paths look like "/about"; links point to "#/about", which also works on static hosting.

type RouteProps = { path: string; element: ReactNode }

function currentPath(): string {
  const path = window.location.hash.replace(/^#/, '')
  return path.startsWith('/') ? path : '/'
}

/** The current page path, updated when the user follows a link or presses back. */
export function useRoute(): string {
  const [path, setPath] = useState(currentPath)
  useEffect(() => {
    const update = () => setPath(currentPath())
    window.addEventListener('hashchange', update)
    return () => window.removeEventListener('hashchange', update)
  }, [])
  return path
}

export function navigate(to: string): void {
  window.location.hash = to
}

export function Router({ children }: { children: ReactNode }) {
  return <>{children}</>
}

export function Link({ to, className, children }: { to: string; className?: string; children: ReactNode }) {
  return (
    <a href={'#' + to} className={className}>
      {children}
    </a>
  )
}

/** Declares a page inside <Routes>; path "*" is the page shown for unknown addresses. */
export function Route(props: RouteProps): ReactNode {
  return props.element
}

export function Routes({ children }: { children: ReactNode }) {
  const path = useRoute()
  let fallback: ReactNode = (
    <main className="mx-auto max-w-xl p-8 text-center">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <a href="#/" className="mt-4 inline-block underline">
        Go to the home page
      </a>
    </main>
  )
  for (const child of Children.toArray(children)) {
    if (!isValidElement<RouteProps>(child) || child.type !== Route) continue
    if (child.props.path === path) return <>{child.props.element}</>
    if (child.props.path === '*') fallback = child.props.element
  }
  return <>{fallback}</>
}
