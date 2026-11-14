import { useEffect, useState, type FormEvent } from 'react'
import { KeyRound, LogOut, ShieldCheck, Users } from 'lucide-react'
import { Avatar, Badge, Button, EmptyState, Grid, Notice, PageHeader, Skeleton, TextField, toast } from '{{kit}}'
import { auth, useSession } from './auth-client'

type Member = { id: string; email: string; role: 'admin' | 'member' }

async function call<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth.getToken() ?? ''}` },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (response.status === 401) auth.signOut()
  if (!response.ok) throw new Error(`Request failed (${response.status})`)
  return await response.json() as T
}

function PasswordCard() {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [repeat, setRepeat] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (next.length < 8) { setError('Use at least 8 characters for the new password.'); return }
    if (next !== repeat) { setError('The new passwords do not match.'); return }
    setBusy(true)
    setError('')
    try {
      await call('/api/auth/password', 'POST', { current, next })
      setCurrent('')
      setNext('')
      setRepeat('')
      toast('Password updated')
    } catch (failure) {
      setError(String(failure).includes('403') ? 'Your current password is not correct.' : 'Could not update the password. Please try again.')
    } finally {
      setBusy(false)
    }
  }
  return <section className="app-card app-stack">
    <div className="app-record__head">
      <span className="app-icon-tile"><KeyRound size={20} aria-hidden="true" /></span>
      <div className="app-stack" style={{ gap: '0.2rem' }}><h2 style={{ margin: 0 }}>Password</h2><p className="app-muted">Change the password you sign in with.</p></div>
    </div>
    <form className="app-stack" onSubmit={submit} noValidate>
      {error && <Notice tone="danger">{error}</Notice>}
      <TextField id="account-current-password" label="Current password" type="password" autoComplete="current-password" value={current} onChange={event => setCurrent(event.target.value)} />
      <TextField id="account-new-password" label="New password" type="password" autoComplete="new-password" hint="At least 8 characters." value={next} onChange={event => setNext(event.target.value)} />
      <TextField id="account-repeat-password" label="Repeat new password" type="password" autoComplete="new-password" value={repeat} onChange={event => setRepeat(event.target.value)} />
      <div className="app-actions"><Button type="submit" id="account-save-password" disabled={busy}>{busy ? 'Saving…' : 'Update password'}</Button></div>
    </form>
  </section>
}

function TeamCard({ me }: { me: string }) {
  const [members, setMembers] = useState<Member[] | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState('')
  const [version, setVersion] = useState(0)
  useEffect(() => {
    let active = true
    call<Member[]>('/api/auth/users')
      .then(rows => { if (active) { setMembers(rows); setError('') } })
      .catch(() => { if (active) setError('Could not load the people using this app.') })
    return () => { active = false }
  }, [version])
  async function promote(member: Member) {
    setBusy(member.id)
    try {
      await call('/api/auth/admins', 'POST', { email: member.email })
      toast(`${member.email} is now an admin`)
      setVersion(value => value + 1)
    } catch {
      toast('Could not change this role. Please try again.', 'danger')
    } finally {
      setBusy('')
    }
  }
  return <section className="app-card app-stack">
    <div className="app-record__head">
      <span className="app-icon-tile"><Users size={20} aria-hidden="true" /></span>
      <div className="app-stack" style={{ gap: '0.2rem' }}><h2 style={{ margin: 0 }}>People</h2><p className="app-muted">Admins can change shared content. Make a trusted person an admin.</p></div>
    </div>
    {error && <Notice tone="danger">{error}</Notice>}
    {members === null ? !error && <Skeleton lines={3} />
      : members.length <= 1 ? <EmptyState icon={<Users size={22} />} title="Only you so far" description="People who sign up will appear here." />
        : <dl className="app-props">
          {members.map(member => <div key={member.id}>
            <dt><Avatar name={member.email} size={30} />{member.email}{member.id === me && <span className="app-muted"> (you)</span>}</dt>
            <dd>{member.role === 'admin'
              ? <Badge tone="accent">Admin</Badge>
              : <Button size="sm" variant="secondary" disabled={busy === member.id} onClick={() => promote(member)}>Make admin</Button>}</dd>
          </div>)}
        </dl>}
  </section>
}

export default function ProfilePage() {
  const session = useSession()
  if (!session) return <EmptyState icon={<KeyRound size={22} />} title="Sign in to see your account"
    action={<a className="app-button" href="#/sign-in">Sign in</a>} />
  const admin = session.role === 'admin'
  return <div className="app-stack">
    <PageHeader eyebrow="Account" title="Your account" description="Your sign-in details and who can manage this app." />
    <section className="app-card">
      <div className="app-detail-hero">
        <Avatar name={session.email} size={56} />
        <div className="app-stack" style={{ gap: '0.35rem' }}>
          <strong style={{ fontSize: '1.15rem', overflowWrap: 'anywhere' }}>{session.email}</strong>
          <div className="app-record__meta">
            {admin ? <Badge tone="accent"><ShieldCheck size={14} aria-hidden="true" />Admin</Badge> : <Badge>Member</Badge>}
          </div>
        </div>
        <Button variant="ghost" onClick={() => { auth.signOut(); window.location.hash = '#/sign-in' }}><LogOut size={16} aria-hidden="true" />Sign out</Button>
      </div>
    </section>
    <Grid min={22}>
      <PasswordCard />
      {admin && <TeamCard me={session.userId} />}
    </Grid>
  </div>
}
