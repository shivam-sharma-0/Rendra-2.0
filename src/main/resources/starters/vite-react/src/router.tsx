import { Children, isValidElement, useEffect, useState, type AnchorHTMLAttributes, type ReactNode } from 'react'

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

export interface LinkProps extends Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> {
  to: string
}

export function Link({ to, children, ...props }: LinkProps) {
  return (
    <a {...props} href={'#' + to}>
      {children}
    </a>
  )
}

/** Declares a page inside <Routes>; path "*" is the page shown for unknown addresses. */
export function Route(props: RouteProps): ReactNode {
  return props.element
}

/** The built-in page for unknown addresses; use it in <Route path="*" element={<NotFound />} /> or restyle it. */
export function NotFound() {
  return (
    <main className="mx-auto max-w-xl p-8 text-center">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <a href="#/" className="mt-4 inline-block underline">
        Go to the home page
      </a>
    </main>
  )
}

export function Routes({ children }: { children: ReactNode }) {
  const path = useRoute()
  let fallback: ReactNode = <NotFound />
  for (const child of Children.toArray(children)) {
    if (!isValidElement<RouteProps>(child) || child.type !== Route) continue
    if (child.props.path === path) return <>{child.props.element}</>
    if (child.props.path === '*') fallback = child.props.element
  }
  return <>{fallback}</>
}
