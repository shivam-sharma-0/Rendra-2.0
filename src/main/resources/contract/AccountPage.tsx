import { useState, type FormEvent } from 'react'
import { auth, useSession } from './auth-client'

export default function AccountPage() {
  const session = useSession()
  const [mode, setMode] = useState<'login' | 'signup'>(window.location.hash.includes('sign') || window.location.hash.includes('register') ? 'signup' : 'login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const creating = mode === 'signup'

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (password.length < 8) { setError('Use at least 8 characters for the password.'); return }
    setBusy(true)
    try {
      if (creating) await auth.signUp({ email, password })
      else await auth.signIn({ email, password })
      window.location.hash = '#/'
    } catch (failure) {
      const status = failure instanceof Error ? failure.message.match(/\((\d{3})\)/)?.[1] : undefined
      setError(status === '409' ? 'An account with this email already exists. Sign in instead.'
        : status === '401' || status === '400' ? (creating ? 'Check the email address and password.' : 'Wrong email or password.')
        : 'The account service is unavailable right now. Try again in a moment.')
    } finally {
      setBusy(false)
    }
  }

  if (session) {
    return (
      <section className="app-card app-stack" style={{ maxWidth: '28rem', margin: '2rem auto' }}>
        <h1>You are signed in</h1>
        <p className="app-muted">{session.email}</p>
        <div style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap' }}>
          <a className="app-button" href="#/">Continue</a>
          <button type="button" onClick={() => auth.signOut()}>Sign out</button>
        </div>
      </section>
    )
  }

  return (
    <section className="app-card app-stack" style={{ maxWidth: '28rem', margin: '2rem auto' }}>
      <h1>{creating ? 'Create your account' : 'Sign in'}</h1>
      <p className="app-muted">{creating ? 'Your data is private to your account.' : 'Welcome back.'}</p>
      {error && <p role="alert">{error}</p>}
      <form onSubmit={submit} data-account-form={mode}>
        <label>
          Email
          <input type="email" name="email" autoComplete="email" value={email} onChange={event => setEmail(event.target.value)} required />
        </label>
        <label>
          Password
          <input type="password" name="password" autoComplete={creating ? 'new-password' : 'current-password'} minLength={8}
            value={password} onChange={event => setPassword(event.target.value)} required />
          <small className="app-muted">At least 8 characters.</small>
        </label>
        <button type="submit" data-action={creating ? 'signup' : 'login'} disabled={busy}>
          {busy ? 'Please wait…' : creating ? 'Create account' : 'Sign in'}
        </button>
      </form>
      <p className="app-muted">
        {creating ? 'Already have an account? ' : 'New here? '}
        <button type="button" onClick={() => { setMode(creating ? 'login' : 'signup'); setError(null) }} style={{ padding: 0, border: 0, background: 'none', color: 'var(--app-accent)', minHeight: 0 }}>
          {creating ? 'Sign in' : 'Create an account'}
        </button>
      </p>
    </section>
  )
}
