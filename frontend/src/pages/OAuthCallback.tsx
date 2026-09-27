import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import AuroraBackground from '../components/AuroraBackground'
import Logo from '../components/Logo'
import { startProject, type ProjectType } from '../api/projects'

/**
 * Landing spot for the backend's post-OAuth redirect. The refresh token was set as an
 * HttpOnly cookie during the redirect, so there are no tokens in the URL. The AuthProvider's
 * silent refresh restores the session; we just wait for it and route on (or show an error).
 */
export default function OAuthCallback() {
  const { isAuthenticated, loading } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  // Ensure the post-auth routing (which consumes the one-shot pending prompt) runs exactly once,
  // even under React StrictMode's double-invoked effects in development.
  const handled = useRef(false)

  useEffect(() => {
    // Surface a provider error passed back in the fragment (#error=…).
    const err = new URLSearchParams(window.location.hash.replace(/^#/, '')).get('error')
    if (err) {
      window.history.replaceState({}, document.title, window.location.pathname)
      setError(mapError(err))
      return
    }
    if (loading) return
    if (!isAuthenticated) {
      setError('Could not complete sign-in. Please try again.')
      return
    }
    if (handled.current) return
    handled.current = true
    // Carry a guest's pending prompt through the OAuth round-trip: start their project and open it,
    // otherwise land on the dashboard.
    let cancelled = false
    const pending = sessionStorage.getItem('kiln_pending_prompt')
    if (pending) {
      const mode = (sessionStorage.getItem('kiln_pending_mode') || 'BUILD') as ProjectType
      sessionStorage.removeItem('kiln_pending_prompt')
      sessionStorage.removeItem('kiln_pending_mode')
      startProject(pending, mode)
        .then((project) => { if (!cancelled) navigate(`/project/${project.id}`, { replace: true }) })
        .catch(() => { if (!cancelled) navigate('/dashboard', { replace: true }) })
    } else {
      navigate('/dashboard', { replace: true })
    }
    return () => { cancelled = true }
  }, [isAuthenticated, loading, navigate])

  return (
    <div className="relative grid min-h-screen place-items-center px-5">
      <AuroraBackground />
      <div className="animate-fade-up card w-full max-w-sm rounded-3xl p-8 text-center">
        <Logo className="justify-center" showWordmark={false} />
        {error ? (
          <>
            <h1 className="font-display mt-5 text-xl font-bold text-text">Sign-in failed</h1>
            <p className="mt-2 text-sm text-muted">{error}</p>
            <Link
              to="/"
              className="mt-6 inline-flex items-center gap-2 text-sm font-semibold text-accent hover:underline"
            >
              ← Back home
            </Link>
          </>
        ) : (
          <>
            <h1 className="font-display mt-5 text-xl font-bold text-text">Signing you in…</h1>
            <p className="mt-2 text-sm text-muted">Finishing up your Google sign-in.</p>
            <div className="mx-auto mt-6 h-6 w-6 animate-spin rounded-full border-2 border-[color:var(--border-strong)] border-t-[color:var(--accent)]" />
          </>
        )}
      </div>
    </div>
  )
}

function mapError(code: string): string {
  switch (code) {
    case 'email_unavailable':
      return 'Google did not share an email for this account.'
    case 'oauth_failed':
      return 'Google sign-in did not complete.'
    default:
      return 'Sign-in was cancelled or failed.'
  }
}
