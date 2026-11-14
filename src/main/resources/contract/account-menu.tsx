const ACCOUNT_PAGE: string | null = {{accountPage}}

function AccountMenu() {
  const session = useSession()
  if (!session) return <a className="app-button" href="{{signIn}}">Sign in</a>
  const initials = session.email.slice(0, 1).toUpperCase()
  return (
    <div className="app-account">
      {ACCOUNT_PAGE
        ? <a className="app-account__link" href={ACCOUNT_PAGE} aria-label="Your account">
            <span className="app-avatar" style={{ width: 32, height: 32 }} aria-hidden="true">{initials}</span>
            <span className="app-account__email">{session.email}</span>
          </a>
        : <span className="app-muted app-account__email">{session.email}</span>}
      <button type="button" onClick={() => { auth.signOut(); window.location.hash = '{{signIn}}' }}>Sign out</button>
    </div>
  )
}
