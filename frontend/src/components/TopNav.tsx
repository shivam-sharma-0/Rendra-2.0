import { useAuth } from '../auth/AuthContext'
import Button from './ui/Button'
import Logo from './Logo'
import ThemeToggle from './ThemeToggle'

const LINKS = ['Product', 'Templates', 'Pricing', 'Docs']

type Props = { onSignIn: () => void }

export default function TopNav({ onSignIn }: Props) {
  const { user, isAuthenticated, signOut } = useAuth()

  return (
    <header className="sticky top-0 z-30 bg-transparent">
      <div className="mx-auto flex max-w-7xl items-center justify-between px-5 py-3.5 sm:px-8">
        <div className="flex items-center gap-8">
          <Logo />
          <nav className="hidden items-center gap-6 md:flex">
            {LINKS.map((l) => (
              <a key={l} href="#" className="text-sm font-medium text-muted transition hover:text-text">
                {l}
              </a>
            ))}
          </nav>
        </div>

        <div className="flex items-center gap-2.5">
          <ThemeToggle />
          {isAuthenticated ? (
            <>
              <span className="hidden text-sm text-muted sm:inline">
                {user?.name ?? user?.email}
              </span>
              <Button variant="outline" size="sm" onClick={signOut}>
                Sign out
              </Button>
            </>
          ) : (
            <>
              <Button variant="ghost" size="sm" className="hidden sm:inline-flex" onClick={onSignIn}>
                Log in
              </Button>
              <Button size="sm" onClick={onSignIn}>
                Get started
              </Button>
            </>
          )}
        </div>
      </div>
    </header>
  )
}
