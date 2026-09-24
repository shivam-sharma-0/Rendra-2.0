import { useEffect, useState, type FormEvent } from 'react'
import { useAuth } from '../auth/AuthContext'
import { ApiClientError, googleLoginUrl } from '../auth/api'
import Logo from './Logo'

type Props = {
  open: boolean
  onClose: () => void
  /** Called once a session has been established (context already holds the user). */
  onAuthenticated: () => void
  /** Optional line shown under the title, e.g. the prompt the user tried to run. */
  intent?: string
}

type Mode = 'login' | 'signup'

export default function LoginModal({ open, onClose, onAuthenticated, intent }: Props) {
  const { login, signup } = useAuth()
  const [mode, setMode] = useState<Mode>('login')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Close on Escape.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (busy) return
    setError(null)
    setBusy(true)
    try {
      if (mode === 'signup') {
        await signup(email.trim().toLowerCase(), password, name)
      } else {
        await login(email.trim().toLowerCase(), password)
      }
      onAuthenticated()
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Something went wrong. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  const isSignup = mode === 'signup'

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center px-4"
      role="dialog"
      aria-modal="true"
      onMouseDown={onClose}
    >
      <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" />

      <div
        className="card animate-pop-in relative w-full max-w-sm rounded-3xl p-7"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="absolute right-4 top-4 grid h-8 w-8 place-items-center rounded-full text-muted transition hover:bg-black/[0.05] hover:text-text dark:hover:bg-white/[0.06]"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>

        <Logo showWordmark={false} className="mb-4" />
        <h2 className="font-display text-xl font-bold text-text">
          {isSignup ? 'Create your account.' : 'Welcome back.'}
        </h2>
        <p className="mt-1 text-sm text-muted">
          {intent ? `Sign in to run “${truncate(intent)}”.` : 'Log in or create your account to continue.'}
        </p>

        <div className="mt-6 space-y-2.5">
          <OAuthButton onClick={() => (window.location.href = googleLoginUrl())} label="Continue with Google">
            <path fill="#EA4335" d="M12 10.2v3.9h5.5c-.24 1.4-1.7 4.1-5.5 4.1a6.2 6.2 0 0 1 0-12.4c1.9 0 3.2.8 3.9 1.5l2.7-2.6C16.9 2.6 14.7 1.6 12 1.6a10.4 10.4 0 0 0 0 20.8c6 0 10-4.2 10-10.1 0-.7-.1-1.2-.2-1.8z" />
          </OAuthButton>
          <OAuthButton disabled label="GitHub — coming soon">
            <path fill="currentColor" d="M12 .5A11.5 11.5 0 0 0 8.4 22.9c.6.1.8-.2.8-.6v-2c-3.2.7-3.9-1.5-3.9-1.5-.5-1.3-1.3-1.7-1.3-1.7-1-.7.1-.7.1-.7 1.2.1 1.8 1.2 1.8 1.2 1 1.8 2.7 1.3 3.4 1 .1-.8.4-1.3.7-1.6-2.6-.3-5.3-1.3-5.3-5.7 0-1.3.4-2.3 1.2-3.1-.1-.3-.5-1.5.1-3.1 0 0 1-.3 3.3 1.2a11.5 11.5 0 0 1 6 0C17 4.6 18 4.9 18 4.9c.6 1.6.2 2.8.1 3.1.8.8 1.2 1.8 1.2 3.1 0 4.4-2.7 5.4-5.3 5.7.4.4.8 1.1.8 2.2v3.3c0 .4.2.7.8.6A11.5 11.5 0 0 0 12 .5z" />
          </OAuthButton>
        </div>

        <div className="my-5 flex items-center gap-3 text-xs text-muted">
          <span className="h-px flex-1 bg-border" />
          OR
          <span className="h-px flex-1 bg-border" />
        </div>

        <form onSubmit={submit} className="space-y-2.5">
          {isSignup && (
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Name (optional)"
              autoComplete="name"
              className={inputCls}
            />
          )}
          <input
            type="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="name@work.com"
            autoComplete="email"
            className={inputCls}
          />
          <input
            type="password"
            required
            minLength={isSignup ? 8 : undefined}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={isSignup ? 'Create a password (min 8 chars)' : 'Password'}
            autoComplete={isSignup ? 'new-password' : 'current-password'}
            className={inputCls}
          />

          {error && <p className="text-[13px] font-medium text-danger">{error}</p>}

          <button
            type="submit"
            disabled={busy}
            className="w-full rounded-[10px] bg-[color:var(--spark)] px-4 py-2.5 text-sm font-semibold text-[color:var(--spark-contrast)] shadow-[var(--shadow-pop)] transition hover:bg-[color:var(--spark-hover)] disabled:opacity-60"
          >
            {busy ? 'Please wait…' : isSignup ? 'Create account' : 'Log in'}
          </button>
        </form>

        <p className="mt-5 text-center text-[13px] text-muted">
          {isSignup ? 'Already have an account?' : 'New to Rendra?'}{' '}
          <button
            type="button"
            onClick={() => {
              setMode(isSignup ? 'login' : 'signup')
              setError(null)
            }}
            className="font-semibold text-accent hover:underline"
          >
            {isSignup ? 'Log in' : 'Create account'}
          </button>
        </p>

        <p className="mt-3 text-center text-[11px] leading-relaxed text-faint">
          By continuing you agree to our{' '}
          <a href="#" className="text-accent hover:underline">Terms</a> &{' '}
          <a href="#" className="text-accent hover:underline">Privacy Policy</a>.
        </p>
      </div>
    </div>
  )
}

const inputCls =
  'w-full rounded-[10px] border border-border bg-black/[0.03] px-3.5 py-2.5 text-sm text-text outline-none transition ' +
  'placeholder:text-muted/60 focus-visible:border-[color:var(--accent)] focus-visible:ring-2 focus-visible:ring-[color:var(--ring)] dark:bg-white/[0.04]'

function OAuthButton({
  onClick,
  label,
  children,
  disabled,
}: {
  onClick?: () => void
  label: string
  children: React.ReactNode
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="flex w-full items-center justify-center gap-3 rounded-[10px] border border-[color:var(--border-strong)] bg-[color:var(--surface)] px-4 py-2.5 text-sm font-semibold text-text transition enabled:hover:-translate-y-px enabled:hover:shadow-[var(--shadow-pop)] disabled:cursor-not-allowed disabled:opacity-50"
    >
      <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden>{children}</svg>
      {label}
    </button>
  )
}

function truncate(s: string, n = 42) {
  return s.length > n ? `${s.slice(0, n)}…` : s
}
