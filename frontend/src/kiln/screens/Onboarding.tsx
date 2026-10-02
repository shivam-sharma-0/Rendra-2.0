import { useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { memberApi, workspaceApi, type WorkspaceRole } from '../../api/projects'

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
    <div className="kob h-screen flex bg-[var(--bg)] text-[var(--ink)] font-['Geist',system-ui,sans-serif] overflow-hidden animate-[kob-fade_.26s_ease_both] motion-reduce:animate-none">
      <aside className="relative w-[460px] shrink-0 my-3 ml-3 rounded-[28px] bg-[var(--rail)] text-[var(--bg)] py-9 px-10 flex flex-col gap-11 overflow-hidden max-[980px]:hidden">
        <span className="absolute right-[-120px] bottom-[-120px] w-[360px] h-[360px] rounded-full opacity-50 bg-[conic-gradient(from_0deg,#FF5B2E,#FFB23E,#5B4CF5,#FF5B2E)] animate-[kob-spin_12s_linear_infinite]" aria-hidden="true" />
        <span className="absolute right-[-40px] bottom-[-40px] w-[200px] h-[200px] rounded-full bg-[var(--rail)]" aria-hidden="true" />
        <Link to="/" className="flex items-center gap-2.5 text-[var(--bg)] relative no-underline">
          <span className="w-7 h-7 rounded-full grid place-items-center bg-[conic-gradient(from_0deg,#FF5B2E,#FFB23E,#5B4CF5,#FF5B2E)]"><i className="w-[11px] h-[11px] rounded-full bg-[var(--rail)]" /></span>
          <span className="font-['Bricolage_Grotesque',sans-serif] font-extrabold text-[21px] tracking-[-0.03em]">Kiln</span>
        </Link>

        <ol className="flex flex-col relative">
          {RAIL_STEPS.map((s, i) => {
            const n = i + 1
            const done = n < step
            const cur = n === step
            return (
              <li className="flex gap-4" key={s.title}>
                <div className="flex flex-col items-center w-8">
                  <span className={`w-8 h-8 rounded-2xl grid place-items-center text-[13px] font-bold shrink-0 transition-[background] duration-300 border-[1.5px] ${done ? 'bg-[var(--accent)] text-white border-transparent' : cur ? 'bg-[var(--bg)] text-[var(--ink)] border-transparent' : 'bg-transparent text-[var(--rail-dim)] border-[var(--muted)]'}`}>
                    {done ? <Ico d="M20 6 9 17l-5-5" size={14} /> : n}
                  </span>
                  {i < 2 && <span className={`w-0.5 grow min-h-[30px] ${done ? 'bg-[var(--accent)]' : 'bg-[var(--rail-line)]'}`} />}
                </div>
                <div className="flex flex-col gap-1 pt-[5px] pb-[26px]">
                  <span className={`text-base font-semibold ${cur || done ? 'text-[var(--bg)]' : 'text-[var(--rail-dim)]'}`}>{s.title}</span>
                  <span className="text-[13.5px] text-[var(--rail-dim)] leading-[1.45]">{s.sub}</span>
                </div>
              </li>
            )
          })}
        </ol>

        <div className="relative p-[18px] rounded-[20px] bg-[var(--rail-2)] border border-[var(--rail-line)] flex flex-col gap-3.5 max-w-[320px] mt-auto">
          <span className="text-[11px] font-semibold tracking-[0.08em] uppercase text-[var(--rail-dim)]">Preview</span>
          <div className="flex items-center gap-3">
            <span className="w-[42px] h-[42px] rounded-xl text-white font-['Bricolage_Grotesque',sans-serif] font-extrabold text-lg grid place-items-center" style={{ background: color }}>{wsInitial}</span>
            <span style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
              <span className="text-[15px] font-semibold whitespace-nowrap overflow-hidden text-ellipsis">{wsShown}</span>
              <span className="font-['JetBrains_Mono',monospace] text-xs text-[var(--rail-dim)]">kiln.app/{slug || 'workspace'}</span>
            </span>
          </div>
          <div className="flex items-center gap-2.5">
            <span style={{ display: 'flex' }}>
              <span className="w-7 h-7 rounded-[14px] border-2 border-[var(--rail-2)] bg-[var(--bg)] text-[var(--ink)] text-[10px] font-bold grid place-items-center">AP</span>
              {step === 3 && filledInvites.map((inv, i) => (
                <span className="w-7 h-7 rounded-[14px] border-2 border-[var(--rail-2)] text-white text-[10px] font-bold grid place-items-center -ml-2" key={i} style={{ background: FACE_COLORS[i % 5] }}>
                  {inv.email.slice(0, 2).toUpperCase()}
                </span>
              ))}
            </span>
            <span className="text-[12.5px] text-[#C9C6BE]">{faceLabel}</span>
          </div>
        </div>
      </aside>

      <main className="grow min-w-0 flex flex-col pt-9 px-24 pb-8 overflow-y-auto max-[980px]:py-8 max-[980px]:px-6">
        <div className="flex justify-end text-[13px] text-[var(--faint)]">Step {step} of 3</div>
        <div className="h-1 rounded-sm bg-[var(--line)] mt-3 overflow-hidden"><div className="h-1 rounded-sm bg-[var(--accent)] transition-[width] duration-500 ease-[cubic-bezier(.2,.8,.2,1)]" style={{ width: `${(step / 3) * 100}%` }} /></div>

        <div className="grow flex flex-col justify-center gap-[30px] max-w-[720px]">
          {step === 1 && (
            <>
              <div>
                <h1 className="m-0 font-['Bricolage_Grotesque',sans-serif] font-extrabold text-[52px] leading-[1.02] tracking-[-0.045em]">Nice to meet you, <span className="font-['Instrument_Serif',serif] italic font-normal text-[var(--accent)]">{firstName}</span>.</h1>
                <p className="text-[17px] text-[var(--muted)]">Two quick questions so Kiln can tailor templates and suggestions.</p>
              </div>
              <div className="flex flex-col gap-3">
                <span className="text-sm font-semibold">What best describes you?</span>
                <div className="grid grid-cols-4 gap-2.5 max-[980px]:grid-cols-2">
                  {ROLES.map((r) => (
                    <button type="button" key={r.id} aria-pressed={role === r.id}
                      className={`h-[88px] p-3.5 rounded-2xl flex flex-col justify-between items-start text-sm font-semibold text-left border ${role === r.id ? 'bg-[var(--ink)] text-white border-[var(--ink)] shadow-[0_14px_24px_-16px_rgba(22,21,26,.6)]' : 'bg-[var(--card)] text-[var(--ink)] border-[var(--line-2)]'}`} onClick={() => setRole(r.id)}>
                      <Ico d={r.icon} /> {r.label}
                    </button>
                  ))}
                </div>
              </div>
              <div className="flex flex-col gap-3">
                <span className="text-sm font-semibold">What do you want to build first? <small className="font-normal text-[var(--faint)]">Pick any</small></span>
                <div className="flex flex-wrap gap-2">
                  {GOALS.map((g) => {
                    const on = goals.includes(g.id)
                    return (
                      <button type="button" key={g.id} aria-pressed={on}
                        className={`h-[42px] px-4 rounded-[21px] text-sm font-medium flex items-center gap-[7px] border ${on ? 'bg-[#FFF1EB] text-[#A33511] border-[#FFC9B5]' : 'bg-[var(--card)] text-[var(--ink)] border-[var(--line-2)]'}`} onClick={() => toggleGoal(g.id)}>
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
                <h1 className="m-0 font-['Bricolage_Grotesque',sans-serif] font-extrabold text-[52px] leading-[1.02] tracking-[-0.045em]">Create your <span className="font-['Instrument_Serif',serif] italic font-normal text-[var(--accent)]">workspace</span></h1>
                <p className="text-[17px] text-[var(--muted)]">A workspace holds your projects, teammates, connectors and credits.</p>
              </div>
              <div className="grid grid-cols-2 gap-4 max-[980px]:grid-cols-1">
                <div className="flex flex-col gap-[7px]">
                  <label htmlFor="ws-name" className="text-[13px] font-semibold text-[var(--muted)]">Workspace name</label>
                  <input id="ws-name" className="h-[52px] px-4 rounded-[14px] border border-[var(--line-2)] bg-[var(--card)] text-[15px] text-[var(--ink)] outline-[var(--violet)] aria-[invalid=true]:border-[#C63B2B] aria-[invalid=true]:bg-[#FDF3F1]" value={wsName} onChange={(e) => onName(e.target.value)}
                    aria-invalid={nameError ? true : undefined} />
                  {nameError && <span className="text-[#C63B2B] text-[12.5px] font-medium" role="alert">{nameError}</span>}
                </div>
                <div className="flex flex-col gap-[7px]">
                  <label htmlFor="ws-slug" className="text-[13px] font-semibold text-[var(--muted)]">Workspace URL</label>
                  <div className="h-[52px] rounded-[14px] border border-[var(--line-2)] bg-[var(--card)] flex items-center px-4 font-['JetBrains_Mono',monospace] text-sm">
                    <span className="text-[var(--faint)]">kiln.app/</span>
                    <input id="ws-slug" className="grow min-w-0 border-none outline-none bg-transparent font-[inherit] text-sm text-[var(--ink)]" value={slug} onChange={(e) => setSlug(slugify(e.target.value))} />
                    <em className="font-['Geist',sans-serif] not-italic text-xs font-semibold text-[#0B6B45]">Available</em>
                  </div>
                </div>
              </div>
              <div className="flex flex-col gap-3">
                <span className="text-sm font-semibold">Who is it for?</span>
                <div className="grid grid-cols-3 gap-3 max-[980px]:grid-cols-1">
                  {KINDS.map((k) => (
                    <button type="button" key={k.id} aria-pressed={kind === k.id}
                      className={`p-4 rounded-[18px] flex flex-col gap-2 text-left text-[var(--ink)] bg-[var(--card)] border-2 ${kind === k.id ? 'border-[var(--ink)] shadow-[0_14px_24px_-18px_rgba(22,21,26,.6)]' : 'border-[var(--line-3)]'}`} onClick={() => setKind(k.id)}>
                      <span className="w-10 h-10 rounded-xl text-[var(--ink)] grid place-items-center" style={{ background: k.tint }}><Ico d={k.icon} size={19} /></span>
                      <b className="text-[15px] font-semibold">{k.label}</b>
                      <span className="text-[13px] text-[var(--muted)] leading-[1.45]">{k.desc}</span>
                    </button>
                  ))}
                </div>
              </div>
              <div className="flex items-center gap-3">
                <span className="text-sm font-semibold">Colour</span>
                {COLORS.map((c) => (
                  <button type="button" key={c.c} aria-label={c.name}
                    className={`w-[34px] h-[34px] rounded-[17px] border-[3px] border-[var(--bg)] ${color === c.c ? 'shadow-[0_0_0_2px_currentColor]' : ''}`}
                    style={{ background: c.c, color: c.c }} onClick={() => setColor(c.c)} />
                ))}
              </div>
            </>
          )}

          {step === 3 && (
            <>
              <div>
                <h1 className="m-0 font-['Bricolage_Grotesque',sans-serif] font-extrabold text-[52px] leading-[1.02] tracking-[-0.045em]">Build <span className="font-['Instrument_Serif',serif] italic font-normal text-[var(--accent)]">together</span></h1>
                <p className="text-[17px] text-[var(--muted)]">Teammates share projects, one chat per project and the workspace credits. You can invite more later.</p>
              </div>
              <div className="flex flex-col gap-3">
                {invites.map((inv, i) => (
                  <div className="flex gap-2" key={i}>
                    <input className="grow h-[52px] px-4 rounded-[14px] border border-[var(--line-2)] bg-[var(--card)] text-[15px] text-[var(--ink)] outline-[var(--violet)]" type="email" placeholder="teammate@company.com" value={inv.email}
                      aria-label={`Teammate email ${i + 1}`}
                      onChange={(e) => setInvites((rows) => rows.map((r, j) => (j === i ? { ...r, email: e.target.value } : r)))} />
                    <button type="button" className="w-[130px] h-[52px] rounded-[14px] border border-[var(--line-2)] bg-[var(--card)] text-[var(--ink)] text-sm font-medium flex items-center justify-center gap-1.5"
                      onClick={() => setInvites((rows) => rows.map((r, j) =>
                        j === i ? { ...r, role: INVITE_ROLES[(INVITE_ROLES.indexOf(r.role) + 1) % INVITE_ROLES.length] } : r))}>
                      {inv.role} <Ico d="m6 9 6 6 6-6" size={12} />
                    </button>
                  </div>
                ))}
                <button type="button" className="self-start h-[38px] px-3 rounded-[10px] border-none bg-transparent text-[var(--ink)] text-sm font-semibold flex items-center gap-[7px] hover:bg-[var(--line-3)]" onClick={() => setInvites((r) => [...r, { email: '', role: 'Editor' }])}>
                  <Ico d="M12 5v14M5 12h14" size={15} /> Add another
                </button>
              </div>
              <div className="flex items-center gap-3.5 p-4 px-[18px] rounded-[18px] bg-[var(--card)] border border-[var(--line)]">
                <span className="w-10 h-10 rounded-xl bg-[var(--violet-soft)] text-[var(--violet-ink)] grid place-items-center"><Ico d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" /></span>
                <span style={{ display: 'flex', flexDirection: 'column', gap: 2, flexGrow: 1 }}>
                  <b className="text-[14.5px] font-semibold">Or share an invite link</b>
                  <small className="font-['JetBrains_Mono',monospace] text-[12.5px] text-[var(--faint)]">kiln.app/join/{slug || 'workspace'}</small>
                </span>
                <button type="button" className="h-[38px] px-3.5 rounded-[11px] border border-[var(--line-2)] bg-[var(--bg)] text-[var(--ink)] text-[13px] font-semibold" onClick={() => {
                  void navigator.clipboard?.writeText(`https://kiln.app/join/${slug || 'workspace'}`).catch(() => {})
                  setCopied(true)
                }}>{copied ? 'Copied' : 'Copy link'}</button>
              </div>
            </>
          )}
        </div>

        <div className="flex items-center gap-2.5 max-w-[720px]">
          {step > 1 && (
            <button type="button" className="h-[50px] px-[18px] rounded-[14px] border border-[var(--line-2)] bg-[var(--card)] text-[var(--ink)] text-[14.5px] font-semibold flex items-center gap-2 hover:bg-[#EEEBE4]" onClick={() => setStep((s) => Math.max(1, s - 1))} disabled={busy}>
              <Ico d="M19 12H5M11 18l-6-6 6-6" size={15} /> Back
            </button>
          )}
          <div style={{ flexGrow: 1 }} />
          {step === 3 ? (
            <>
              <button type="button" className="h-[50px] px-4 rounded-[14px] border-none bg-transparent text-[var(--muted)] text-[14.5px] font-semibold" onClick={skip} disabled={busy}>Skip for now</button>
              <button type="button" className="h-[50px] px-[22px] rounded-[14px] border-none bg-[var(--ink)] text-white text-[14.5px] font-semibold flex items-center gap-2 shadow-[0_14px_26px_-14px_rgba(22,21,26,.6)] disabled:opacity-60 disabled:cursor-not-allowed" onClick={finish} disabled={busy}>
                {busy ? 'Finishing…' : filledInvites.length ? `Send ${filledInvites.length} invite${filledInvites.length > 1 ? 's' : ''} & start` : 'Finish setup'}
                <Ico d="M5 12h14M13 6l6 6-6 6" size={15} />
              </button>
            </>
          ) : (
            <button type="button" className="h-[50px] px-[22px] rounded-[14px] border-none bg-[var(--ink)] text-white text-[14.5px] font-semibold flex items-center gap-2 shadow-[0_14px_26px_-14px_rgba(22,21,26,.6)] disabled:opacity-60 disabled:cursor-not-allowed" onClick={onContinue} disabled={busy}>
              {busy ? 'Please wait…' : 'Continue'} <Ico d="M5 12h14M13 6l6 6-6 6" size={15} />
            </button>
          )}
        </div>
      </main>
    </div>
  )
}
