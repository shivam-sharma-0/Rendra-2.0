
function AccountMenu() {
  const session = useSession()
  if (!session) return <a className="app-button" href="{{signIn}}">Sign in</a>
  return (
    <div className="app-account">
      <span className="app-muted">{session.email}</span>
      <button type="button" onClick={() => { auth.signOut(); window.location.hash = '{{signIn}}' }}>Sign out</button>
    </div>
  )
}
