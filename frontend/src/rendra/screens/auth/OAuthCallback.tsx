import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { startProject } from '../../../api/projects'
import { useAuth } from '../../../auth/AuthContext'
import { AppIcon } from '../../../components/icons'
import { NeuButton } from '../../../components/neu'
import { Spinner } from '../../../components/ui/States'
import { takePendingPrompt } from '../../pendingPrompt'
import CenteredScreen from '../../ui/CenteredScreen'

/**
 * Landing spot for the backend's post-OAuth redirect. The refresh token was set as an
 * HttpOnly cookie during the redirect, so there are no tokens in the URL. The AuthProvider's
 * silent refresh restores the session; we just wait for it and route on (or show an error).
 */
export default function OAuthCallback() {
  const { isAuthenticated, loading } = useAuth()
  const navigate = useNavigate()
  // A provider error passed back in the fragment (#error=…). Read once: the fragment is cleared
  // below, and the routing effect runs again when the session settles (and twice under StrictMode).
  const [providerError] = useState(() => new URLSearchParams(window.location.hash.replace(/^#/, '')).get('error'))
  const error = providerError
    ? mapError(providerError)
    : !loading && !isAuthenticated ? 'Could not complete sign-in. Please try again.' : null
  // Ensure the post-auth routing (which consumes the one-shot pending prompt) runs exactly once,
  // even under React StrictMode's double-invoked effects in development.
  const handled = useRef(false)

  useEffect(() => {
    if (providerError) window.history.replaceState({}, document.title, window.location.pathname)
  }, [providerError])

  useEffect(() => {
    if (providerError || loading || !isAuthenticated) return
    if (handled.current) return
    handled.current = true
    // The backend adds `?new=1` when this sign-in created the account, so a new user starts onboarding.
    const home = new URLSearchParams(window.location.search).get('new') === '1' ? '/onboarding/about-you' : '/dashboard'
    // Carry a guest's pending prompt through the OAuth round-trip: start their project and open it.
    // Not cancelled on cleanup: StrictMode re-runs this effect and `handled` skips the second run,
    // so the first run must be allowed to finish and navigate.
    const pending = takePendingPrompt()
    if (pending) {
      startProject(pending.prompt, pending.mode)
        .then((project) => navigate(`/project/${project.id}`, { replace: true }))
        .catch(() => navigate(home, { replace: true }))
    } else {
      navigate(home, { replace: true })
    }
  }, [providerError, isAuthenticated, loading, navigate])

  return (
    <CenteredScreen busy={!error}>
      {error ? (
        <>
          <span className="cs-icon" aria-hidden><AppIcon name="warning" size={24} /></span>
          <h1>Sign-in failed</h1>
          <p>{error}</p>
          <NeuButton variant="primary" size="lg" block href="/auth">Back to sign in<AppIcon name="arrowRight" /></NeuButton>
        </>
      ) : (
        <div className="cs-loading">
          <Spinner label="Signing you in" />
          <h1>Signing you in…</h1>
          <span>Finishing your sign-in with Google.</span>
        </div>
      )}
    </CenteredScreen>
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
