import { useState, type FormEvent } from 'react'
import { inviteApi, type Member, type WorkspaceRole } from '../../../api/projects'
import { useDailyUsage, useInviteMember, useMembers, useProjects, useRemoveMember, useUpdateMemberRole } from '../../../api/queries'
import { useAuth } from '../../../auth/AuthContext'
import { AppIcon } from '../../../components/icons'
import { NeuAlert, NeuButton, NeuDialog, NeuSelect } from '../../../components/neu'
import { Spinner } from '../../../components/ui/States'
import { cx } from '../../../lib/cx'
import { useTheme, type Theme } from '../../../theme/ThemeProvider'
import { useWorkspace } from '../../../workspace/WorkspaceContext'

const initialsOf = (text: string) => text.split(/\s|@/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase() || 'R'
const ROLE_LABEL: Record<WorkspaceRole, string> = { OWNER: 'Owner', ADMIN: 'Admin', MEMBER: 'Member' }

/** Name, email and the role in the current workspace. Editing them is not available yet. */
export function ProfileSection() {
  const { user } = useAuth()
  const { selected, selectedId } = useWorkspace()
  const { data: members } = useMembers(selectedId)
  const me = members?.find((m) => m.email && m.email === user?.email)
  return (
    <>
      <section className="st-card st-profile">
        <span className="st-avatar">{initialsOf(user?.name || user?.email || '')}</span>
        <div>
          <h3>{user?.name || 'Rendra builder'}</h3>
          <p>{me ? `${ROLE_LABEL[me.role]} of ${selected?.name ?? 'this workspace'}` : selected?.name ?? ''}</p>
        </div>
      </section>
      <dl className="st-facts">
        <div><dt>Full name</dt><dd>{user?.name || '—'}</dd></div>
        <div><dt>Email</dt><dd>{user?.email || '—'}</dd></div>
        <div><dt>Workspace</dt><dd>{selected?.name ?? '—'}</dd></div>
        <div><dt>Role</dt><dd>{me ? ROLE_LABEL[me.role] : '—'}</dd></div>
      </dl>
      <p className="st-note">Changing your name or photo is not available yet.</p>
    </>
  )
}

const THEMES: { value: Theme; label: string }[] = [{ value: 'light', label: 'Light' }, { value: 'dark', label: 'Dark' }, { value: 'system', label: 'System' }]

/** Theme choice (saved in this browser). */
export function AppearanceSection() {
  const { theme, setTheme } = useTheme()
  return (
    <>
      <div className="st-themes" role="radiogroup" aria-label="Theme">
        {THEMES.map((t) => (
          <button key={t.value} type="button" role="radio" aria-checked={theme === t.value} className={cx('st-theme', theme === t.value && 'is-on')} onClick={() => setTheme(t.value)}>
            <span className={`st-theme__preview st-theme__preview--${t.value}`} aria-hidden><i /><b><em /><em /><strong /></b></span>
            <span className="st-theme__label">{t.label}{theme === t.value && <AppIcon name="check" />}</span>
          </button>
        ))}
      </div>
      <p className="st-note">Animations follow your device's “reduce motion” setting.</p>
    </>
  )
}

const NOTIFY = [
  ['Build finished or failed', 'When a build you started completes.'],
  ['Project shared with you', 'When someone gives you access to a project.'],
  ['Workspace invites', 'When you are invited to a workspace.'],
  ['Role changes', 'When your access in a workspace changes.'],
] as const

/** What Rendra notifies about. In-app notifications are always on; email is not available yet. */
export function NotificationsSection() {
  return (
    <section className="st-card st-table" aria-label="Notifications">
      <div className="st-table__row st-table__head"><span>Activity</span><span>In app</span><span>Email</span></div>
      {NOTIFY.map(([title, hint]) => (
        <div className="st-table__row" key={title}>
          <span><b>{title}</b><small>{hint}</small></span>
          <span className="st-on" role="img" aria-label="On"><AppIcon name="check" /></span>
          <span className="st-soon">Coming soon</span>
        </div>
      ))}
    </section>
  )
}

/** Invite people by email or link; change roles; remove members (the owner stays). */
export function MembersSection() {
  const { selectedId } = useWorkspace()
  const { user } = useAuth()
  const { data: members, isLoading } = useMembers(selectedId)
  const invite = useInviteMember(selectedId)
  const updateRole = useUpdateMemberRole(selectedId)
  const removeMember = useRemoveMember(selectedId)
  const [email, setEmail] = useState('')
  const [error, setError] = useState('')
  const [linkCopied, setLinkCopied] = useState(false)
  const [inviteLink, setInviteLink] = useState('')
  const [removing, setRemoving] = useState<Member | null>(null)
  const myRole = members?.find((m) => !!user && m.email === user.email)?.role
  const canManage = myRole === 'OWNER' || myRole === 'ADMIN'

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!email.trim()) return
    setError('')
    try { await invite.mutateAsync(email.trim()); setEmail('') } catch (err) { setError(err instanceof Error ? err.message : 'Could not send the invite.') }
  }
  /** Creates a link invite and copies it; if the clipboard is blocked, the link is shown to copy by hand. */
  const copyLink = async () => {
    if (!selectedId) return
    setError('')
    let link: string
    try {
      const created = await inviteApi.create(selectedId)
      link = `${window.location.origin}/invite?token=${created.token}`
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create an invite link.')
      return
    }
    setInviteLink(link)
    try {
      await navigator.clipboard.writeText(link)
      setLinkCopied(true)
      setTimeout(() => setLinkCopied(false), 1600)
    } catch {
      setLinkCopied(false)
    }
  }

  return (
    <>
      {canManage && <section className="st-card st-invite">
        <h3>Invite people to your workspace</h3>
        <form onSubmit={(e) => void submit(e)}>
          <input className="neu-input" type="email" placeholder="name@company.com" aria-label="Email address" value={email} onChange={(e) => setEmail(e.target.value)} />
          <NeuButton type="submit" variant="primary" disabled={invite.isPending || !email.trim()}>{invite.isPending ? 'Sending…' : 'Send invite'}</NeuButton>
        </form>
        <NeuButton size="sm" onClick={() => void copyLink()}>
          <AppIcon name={linkCopied ? 'check' : 'copy'} />{linkCopied ? 'Link copied' : inviteLink ? 'New invite link' : 'Copy invite link'}
        </NeuButton>
        {inviteLink && (
          <label className="st-link">
            <small>{linkCopied ? 'Copied to your clipboard.' : 'Copy this link and share it to invite people:'}</small>
            <input className="neu-input" readOnly value={inviteLink} onFocus={(e) => e.currentTarget.select()} />
          </label>
        )}
      </section>}
      {error && <NeuAlert>{error}</NeuAlert>}
      {isLoading
        ? <div className="st-center"><Spinner /></div>
        : (members ?? []).length
          ? (
            <ul className="st-members">
              {(members ?? []).map((m) => {
                const self = !!user && m.email === user.email
                return (
                  <li key={m.id}>
                    <span className="st-avatar st-avatar--sm">{initialsOf(m.name || m.email || '')}</span>
                    <span className="st-member"><b>{m.name || m.email}{self && ' (you)'}</b>{m.name && <small>{m.email}</small>}</span>
                    {m.role === 'OWNER' || self || !canManage
                      ? <span className="st-role">{ROLE_LABEL[m.role]}</span>
                      : (
                        <>
                          <NeuSelect compact aria-label={`Role for ${m.email ?? 'member'}`} value={m.role} disabled={updateRole.isPending}
                            onChange={(e) => updateRole.mutate({ memberId: m.id, role: e.target.value as WorkspaceRole })}>
                            <option value="ADMIN">Admin</option>
                            <option value="MEMBER">Member</option>
                          </NeuSelect>
                          <button type="button" className="st-remove" onClick={() => setRemoving(m)} aria-label={`Remove ${m.email ?? 'member'}`} title="Remove member">
                            <AppIcon name="close" />
                          </button>
                        </>
                      )}
                  </li>
                )
              })}
            </ul>
          )
          : <p className="st-note">No members yet — invite someone above.</p>}
      {removing && (
        <NeuDialog
          title="Remove member?"
          size="sm"
          locked={removeMember.isPending}
          onClose={() => setRemoving(null)}
          footer={(
            <>
              <NeuButton onClick={() => setRemoving(null)} disabled={removeMember.isPending}>Cancel</NeuButton>
              <NeuButton variant="danger" disabled={removeMember.isPending}
                onClick={() => removeMember.mutate(removing.id, {
                  onSuccess: () => setRemoving(null),
                  onError: (err) => { setRemoving(null); setError(err instanceof Error ? err.message : 'Could not remove the member.') },
                })}>
                {removeMember.isPending ? 'Removing…' : 'Remove'}
              </NeuButton>
            </>
          )}
        >
          <p className="st-note">{removing.name || removing.email} will lose access to this workspace and its projects.</p>
        </NeuDialog>
      )}
    </>
  )
}

const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 })

/** The plan (every workspace is on Free for now), today's AI usage and the project count. */
export function BillingSection() {
  const { selectedId } = useWorkspace()
  const { data: usage, isError } = useDailyUsage()
  const { data: projects } = useProjects(selectedId)
  const unlimited = usage?.quota === 0
  const percent = usage && !unlimited ? Math.min(100, Math.round(((usage.capacityUsed ?? usage.tokensUsed) / usage.quota) * 100)) : 0
  return (
    <>
      <section className="st-card st-plan">
        <div>
          <small>Current plan</small>
          <h3>Free</h3>
          <p>For exploring your next big idea.</p>
        </div>
        <NeuButton href="/pricing" variant="accent">See plans</NeuButton>
      </section>
      <section className="st-card st-usage">
        <div className="st-usage__head">
          <b>Daily AI usage</b>
          <span>{isError ? 'Unavailable' : !usage ? 'Loading…' : unlimited ? 'No daily limit' : `${percent}% used`}</span>
        </div>
        <div className="st-meter" role="progressbar" aria-label="Daily AI usage" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}><i style={{ width: `${percent}%` }} /></div>
        {usage && (
          <small>
            {compact.format(usage.tokensUsed)}{unlimited ? ' tokens used today' : ` of ${compact.format(usage.quota)} tokens`} · resets {new Date(usage.resetsAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}
            {(usage.unknownTokens ?? 0) > 0 && ` · ${compact.format(usage.unknownTokens!)} held for unreported usage`}
            {(usage.reservedTokens ?? 0) > 0 && ` · ${compact.format(usage.reservedTokens!)} reserved for active calls`}
          </small>
        )}
        <div className="st-usage__row">
          <b>Projects in this workspace</b>
          <span className="st-count">{projects ? projects.totalElements : '—'}</span>
        </div>
      </section>
    </>
  )
}
