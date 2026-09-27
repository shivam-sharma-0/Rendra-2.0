import { useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { memberApi, workspaceApi, type WorkspaceRole } from '../../api/projects'
import './onboarding.css'

/**
 * Onboarding wizard — faithful to Figma "04–06 · Onboarding" (dark rail + 3-step form).
 *
 * Wiring: Step 2 creates (or renames) the real workspace so Step 3 can invite into it; Step 3 sends
 * real member invites. Step 1 persona/goals and the workspace colour/kind/slug are client-side
 * personalization (localStorage) — the backend has no columns for them yet, so persisting them there
 * is deferred to a migration rather than faked. Invite roles map to backend roles (Admin→ADMIN,
 * Editor/Viewer→MEMBER, the two roles the backend currently distinguishes).
 */

type InviteRole = 'Editor' | 'Viewer' | 'Admin'
const INVITE_ROLES: InviteRole[] = ['Editor', 'Viewer', 'Admin']

function Ico({ d, size = 18 }: { d: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"><path d={d} /></svg>
  )
}

const ROLES = [
  { id: 'founder', label: 'Founder', icon: 'M13 2 3 14h9l-1 8 10-12h-9z' },
  { id: 'dev', label: 'Developer', icon: 'M16 18l6-6-6-6M8 6l-6 6 6 6' },
  { id: 'design', label: 'Designer', icon: 'M12 3a9 9 0 1 0 0 18c1 0 1.5-.8 1.5-1.5 0-1.2-1-1.5-1-2.5s.8-1.5 2-1.5H17a4 4 0 0 0 4-4c0-4.7-4-8.5-9-8.5z' },
  { id: 'pm', label: 'Product manager', icon: 'M9 5h11M9 12h11M9 19h11M4 5h.01M4 12h.01M4 19h.01' },
  { id: 'mkt', label: 'Marketer', icon: 'M3 11l18-8-8 18-2-8z' },
  { id: 'agency', label: 'Agency', icon: 'M3 21h18M5 21V7l7-4 7 4v14M9 9h1M14 9h1M9 13h1M14 13h1' },
  { id: 'student', label: 'Student', icon: 'M22 10 12 5 2 10l10 5zM6 12v5c3 2 9 2 12 0v-5' },
  { id: 'other', label: 'Something else', icon: 'M12 5v14M5 12h14' },
]
const GOALS = [
  { id: 'web', label: 'Website' }, { id: 'tool', label: 'Internal tool' }, { id: 'saas', label: 'SaaS product' },
  { id: 'ai', label: 'AI agent or chatbot' }, { id: 'shop', label: 'Online store' }, { id: 'mobile', label: 'Mobile-friendly app' },
]
const KINDS = [
  { id: 'solo', label: 'Just me', desc: 'Personal projects and experiments', tint: '#FFF1EB', icon: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z' },
  { id: 'team', label: 'My team', desc: 'Build together, share credits and connectors', tint: '#F1EEFF', icon: 'M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM23 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8' },
  { id: 'clients', label: 'My clients', desc: 'Hand projects over and invite clients as viewers', tint: '#E4F1F7', icon: 'M3 21h18M5 21V7l7-4 7 4v14' },
]
const COLORS = [
  { name: 'Ink', c: '#16151A' }, { name: 'Ember', c: '#C2410C' }, { name: 'Iris', c: '#5B4CF5' },
  { name: 'Pine', c: '#0B7F58' }, { name: 'Rose', c: '#C92A66' },
]
const RAIL_STEPS = [
  { title: 'About you', sub: 'Your role and what you want to build' },
  { title: 'Your workspace', sub: "Name it and choose who it's for" },
  { title: 'Invite your team', sub: 'Build in the same project, live' },
]
const FACE_COLORS = ['#C92A66', '#1B7FB0', '#0B7F58', '#9A6000', '#5B4CF5']

const slugify = (v: string) =>
  v.toLowerCase().replace(/['']/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')

export default function Onboarding() {
  const nav = useNavigate()
  const { user } = useAuth()
  const { selectedId, setSelectedId, reload } = useWorkspace()

  const firstName = user?.name?.split(' ')[0] || user?.email?.split('@')[0] || 'there'

  const [step, setStep] = useState(1)
  const [role, setRole] = useState('founder')
  const [goals, setGoals] = useState<string[]>(['saas'])
  const [wsName, setWsName] = useState(user?.name ? `${firstName}'s Studio` : 'My Workspace')
  const [slug, setSlug] = useState(() => slugify(user?.name ? `${firstName}s Studio` : 'My Workspace'))
  const [kind, setKind] = useState('team')
  const [color, setColor] = useState('#16151A')
  const [invites, setInvites] = useState<{ email: string; role: InviteRole }[]>([
    { email: '', role: 'Editor' }, { email: '', role: 'Editor' }, { email: '', role: 'Viewer' },
  ])
  const [copied, setCopied] = useState(false)
  const [wsId, setWsId] = useState<string | null>(null)
  const [nameError, setNameError] = useState('')
  const [busy, setBusy] = useState(false)

  const filledInvites = useMemo(() => invites.filter((i) => i.email.trim()), [invites])
  const wsShown = wsName.trim() || 'Your workspace'
  const wsInitial = wsShown.charAt(0).toUpperCase()

  const toggleGoal = (id: string) =>
    setGoals((g) => (g.includes(id) ? g.filter((x) => x !== id) : [...g, id]))

  const onName = (v: string) => {
    setWsName(v)
    setSlug(slugify(v))
    if (nameError) setNameError('')
  }

  const savePersona = () =>
    localStorage.setItem('kiln_persona', JSON.stringify({ role, goals, kind, color, slug }))

  // Leaving step 2 provisions the real workspace (create once, then rename on subsequent visits).
  const ensureWorkspace = async (): Promise<boolean> => {
    if (!wsName.trim()) {
      setNameError('Enter a workspace name')
      return false
    }
    setBusy(true)
    try {
      if (wsId) {
        await workspaceApi.rename(wsId, wsName.trim())
      } else {
        const ws = await workspaceApi.create(wsName.trim())
        setWsId(ws.id)
        setSelectedId(ws.id)
      }
      await reload()
      return true
    } catch (err) {
      setNameError(err instanceof Error ? err.message : 'Could not create the workspace')
      return false
    } finally {
      setBusy(false)
    }
  }

  const onContinue = async () => {
    if (step === 1) {
      setStep(2)
    } else if (step === 2) {
      if (await ensureWorkspace()) setStep(3)
    }
  }

  const finish = async () => {
    const wid = wsId ?? selectedId
    setBusy(true)
    try {
      if (wid) {
        for (const inv of filledInvites) {
          try {
            const member = await memberApi.invite(wid, inv.email.trim())
            if (inv.role === 'Admin') await memberApi.updateRole(wid, member.id, 'ADMIN' as WorkspaceRole)
          } catch {
            /* skip a single failed invite (e.g. already a member) and continue with the rest */
          }
        }
      }
      savePersona()
      nav('/dashboard')
    } finally {
      setBusy(false)
    }
  }

  const skip = () => {
    savePersona()
    nav('/dashboard')
  }

  const faceLabel =
    step === 3
      ? filledInvites.length
        ? `You + ${filledInvites.length} teammate${filledInvites.length > 1 ? 's' : ''}`
        : 'Just you for now'
      : kind === 'solo'
        ? 'Just you'
        : 'You · invite teammates next'

  return (
    <div className="kob">
      <aside className="kob-rail">
        <span className="kob-rail-orb" aria-hidden="true" />
        <span className="kob-rail-orb-mask" aria-hidden="true" />
        <Link to="/" className="kob-brand"><span className="kob-brand-mark"><i /></span><span>Kiln</span></Link>

        <ol className="kob-steps">
          {RAIL_STEPS.map((s, i) => {
            const n = i + 1
            const done = n < step
            const cur = n === step
            return (
              <li className="kob-step" key={s.title}>
                <div className="kob-step-rail">
                  <span className={`kob-dot ${done ? 'done' : cur ? 'cur' : ''}`}>
                    {done ? <Ico d="M20 6 9 17l-5-5" size={14} /> : n}
                  </span>
                  {i < 2 && <span className={`kob-line ${done ? 'done' : ''}`} />}
                </div>
                <div className="kob-step-body">
                  <span className={`kob-step-title ${cur || done ? 'active' : ''}`}>{s.title}</span>
                  <span className="kob-step-sub">{s.sub}</span>
                </div>
              </li>
            )
          })}
        </ol>

        <div className="kob-preview">
          <span className="kob-preview-kicker">Preview</span>
          <div className="kob-preview-ws">
            <span className="kob-preview-badge" style={{ background: color }}>{wsInitial}</span>
            <span style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
              <span className="kob-preview-name">{wsShown}</span>
              <span className="kob-preview-slug">kiln.app/{slug || 'workspace'}</span>
            </span>
          </div>
          <div className="kob-preview-faces">
            <span style={{ display: 'flex' }}>
              <span className="kob-face">AP</span>
              {step === 3 && filledInvites.map((inv, i) => (
                <span className="kob-face" key={i} style={{ background: FACE_COLORS[i % 5] }}>
                  {inv.email.slice(0, 2).toUpperCase()}
                </span>
              ))}
            </span>
            <span className="kob-face-label">{faceLabel}</span>
          </div>
        </div>
      </aside>

      <main className="kob-main">
        <div className="kob-topbar">Step {step} of 3</div>
        <div className="kob-progress"><div className="kob-progress-bar" style={{ width: `${(step / 3) * 100}%` }} /></div>

        <div className="kob-body">
          {step === 1 && (
            <>
              <div>
                <h1 className="kob-h1">Nice to meet you, <span className="kob-serif">{firstName}</span>.</h1>
                <p className="kob-lede">Two quick questions so Kiln can tailor templates and suggestions.</p>
              </div>
              <div className="kob-group">
                <span className="kob-group-label">What best describes you?</span>
                <div className="kob-role-grid">
                  {ROLES.map((r) => (
                    <button type="button" key={r.id} aria-pressed={role === r.id}
                      className={`kob-role ${role === r.id ? 'on' : ''}`} onClick={() => setRole(r.id)}>
                      <Ico d={r.icon} /> {r.label}
                    </button>
                  ))}
                </div>
              </div>
              <div className="kob-group">
                <span className="kob-group-label">What do you want to build first? <small>Pick any</small></span>
                <div className="kob-chips">
                  {GOALS.map((g) => {
                    const on = goals.includes(g.id)
                    return (
                      <button type="button" key={g.id} aria-pressed={on}
                        className={`kob-chip ${on ? 'on' : ''}`} onClick={() => toggleGoal(g.id)}>
                        {on && <Ico d="M20 6 9 17l-5-5" size={13} />} {g.label}
                      </button>
                    )
                  })}
                </div>
              </div>
            </>
          )}

          {step === 2 && (
            <>
              <div>
                <h1 className="kob-h1">Create your <span className="kob-serif">workspace</span></h1>
                <p className="kob-lede">A workspace holds your projects, teammates, connectors and credits.</p>
              </div>
              <div className="kob-grid-2">
                <div className="kob-field">
                  <label htmlFor="ws-name">Workspace name</label>
                  <input id="ws-name" className="kob-input" value={wsName} onChange={(e) => onName(e.target.value)}
                    aria-invalid={nameError ? true : undefined} />
                  {nameError && <span className="kob-error" role="alert">{nameError}</span>}
                </div>
                <div className="kob-field">
                  <label htmlFor="ws-slug">Workspace URL</label>
                  <div className="kob-slug">
                    <span>kiln.app/</span>
                    <input id="ws-slug" value={slug} onChange={(e) => setSlug(slugify(e.target.value))} />
                    <em>Available</em>
                  </div>
                </div>
              </div>
              <div className="kob-group">
                <span className="kob-group-label">Who is it for?</span>
                <div className="kob-kinds">
                  {KINDS.map((k) => (
                    <button type="button" key={k.id} aria-pressed={kind === k.id}
                      className={`kob-kind ${kind === k.id ? 'on' : ''}`} onClick={() => setKind(k.id)}>
                      <span className="kob-kind-icon" style={{ background: k.tint }}><Ico d={k.icon} size={19} /></span>
                      <b>{k.label}</b>
                      <span>{k.desc}</span>
                    </button>
                  ))}
                </div>
              </div>
              <div className="kob-colors">
                <span className="kob-group-label">Colour</span>
                {COLORS.map((c) => (
                  <button type="button" key={c.c} aria-label={c.name}
                    className={`kob-color ${color === c.c ? 'on' : ''}`}
                    style={{ background: c.c, color: c.c }} onClick={() => setColor(c.c)} />
                ))}
              </div>
            </>
          )}

          {step === 3 && (
            <>
              <div>
                <h1 className="kob-h1">Build <span className="kob-serif">together</span></h1>
                <p className="kob-lede">Teammates share projects, one chat per project and the workspace credits. You can invite more later.</p>
              </div>
              <div className="kob-group">
                {invites.map((inv, i) => (
                  <div className="kob-invite-row" key={i}>
                    <input className="kob-input" type="email" placeholder="teammate@company.com" value={inv.email}
                      aria-label={`Teammate email ${i + 1}`}
                      onChange={(e) => setInvites((rows) => rows.map((r, j) => (j === i ? { ...r, email: e.target.value } : r)))} />
                    <button type="button" className="kob-role-select"
                      onClick={() => setInvites((rows) => rows.map((r, j) =>
                        j === i ? { ...r, role: INVITE_ROLES[(INVITE_ROLES.indexOf(r.role) + 1) % INVITE_ROLES.length] } : r))}>
                      {inv.role} <Ico d="m6 9 6 6 6-6" size={12} />
                    </button>
                  </div>
                ))}
                <button type="button" className="kob-add" onClick={() => setInvites((r) => [...r, { email: '', role: 'Editor' }])}>
                  <Ico d="M12 5v14M5 12h14" size={15} /> Add another
                </button>
              </div>
              <div className="kob-link-card">
                <span className="kob-link-icon"><Ico d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" /></span>
                <span style={{ display: 'flex', flexDirection: 'column', gap: 2, flexGrow: 1 }}>
                  <b>Or share an invite link</b>
                  <small>kiln.app/join/{slug || 'workspace'}</small>
                </span>
                <button type="button" className="kob-copy" onClick={() => {
                  void navigator.clipboard?.writeText(`https://kiln.app/join/${slug || 'workspace'}`).catch(() => {})
                  setCopied(true)
                }}>{copied ? 'Copied' : 'Copy link'}</button>
              </div>
            </>
          )}
        </div>

        <div className="kob-footer">
          {step > 1 && (
            <button type="button" className="kob-back" onClick={() => setStep((s) => Math.max(1, s - 1))} disabled={busy}>
              <Ico d="M19 12H5M11 18l-6-6 6-6" size={15} /> Back
            </button>
          )}
          <div style={{ flexGrow: 1 }} />
          {step === 3 ? (
            <>
              <button type="button" className="kob-skip" onClick={skip} disabled={busy}>Skip for now</button>
              <button type="button" className="kob-primary" onClick={finish} disabled={busy}>
                {busy ? 'Finishing…' : filledInvites.length ? `Send ${filledInvites.length} invite${filledInvites.length > 1 ? 's' : ''} & start` : 'Finish setup'}
                <Ico d="M5 12h14M13 6l6 6-6 6" size={15} />
              </button>
            </>
          ) : (
            <button type="button" className="kob-primary" onClick={onContinue} disabled={busy}>
              {busy ? 'Please wait…' : 'Continue'} <Ico d="M5 12h14M13 6l6 6-6 6" size={15} />
            </button>
          )}
        </div>
      </main>
    </div>
  )
}
