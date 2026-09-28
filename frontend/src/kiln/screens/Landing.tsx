import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { startProject, type ProjectType } from '../../api/projects'

const PHRASES = [
  'a booking app for my yoga studio',
  'a CRM that my sales team will love',
  'a portfolio with a dark, editorial feel',
  'an inventory tracker with barcode scan',
]

const MODES: [ProjectType, string][] = [['BUILD', 'Build'], ['PLAN', 'Plan'], ['CHAT', 'Chat']]

const CHIPS: { label: string; icon: string; prompt: string }[] = [
  { label: 'SaaS dashboard', icon: 'M4 20V10M10 20V4M16 20v-7M22 20H2', prompt: 'a SaaS analytics dashboard with charts and team roles' },
  { label: 'Online store', icon: 'M6 6h15l-1.5 9h-12zM6 6 5 3H2M9 20h.01M18 20h.01', prompt: 'an online store for handmade candles with cart and checkout' },
  { label: 'Portfolio', icon: 'M4 5h16v14H4zM4 9h16', prompt: 'a portfolio site for a product designer' },
  { label: 'Booking app', icon: 'M4 5h16v15H4zM4 10h16M9 3v4M15 3v4', prompt: 'a booking app for a hair salon with reminders' },
  { label: 'Internal tool', icon: 'M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.4-.6-.6-2.4z', prompt: 'an internal tool to approve expense requests' },
]

const TEMPLATES = [
  { name: 'Habit tracker', kind: 'Productivity', bg: '#FFEDE6', fg: '#16151A', ac: '#FF5B2E', card: '#FFFFFF' },
  { name: 'Restaurant booking', kind: 'Hospitality', bg: '#1F2A24', fg: '#EAF4EE', ac: '#7BE0A6', card: '#2C3A32' },
  { name: 'Creator storefront', kind: 'E-commerce', bg: '#F1EEFF', fg: '#221A66', ac: '#5B4CF5', card: '#FFFFFF' },
  { name: 'CRM lite', kind: 'Sales', bg: '#16151A', fg: '#F6F4EF', ac: '#FFB23E', card: '#2A2930' },
  { name: 'Event landing', kind: 'Marketing', bg: '#FFF5D6', fg: '#3B2A00', ac: '#E23E7B', card: '#FFFFFF' },
  { name: 'Invoice studio', kind: 'Finance', bg: '#E4F1F7', fg: '#0D2A38', ac: '#1B7FB0', card: '#FFFFFF' },
]

const Arrow = ({ s = 15 }: { s?: number }) => (
  <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
)

export default function Landing() {
  const nav = useNavigate()
  const { isAuthenticated } = useAuth()
  const { selectedId } = useWorkspace()
  const [draft, setDraft] = useState('')
  const [mode, setMode] = useState<ProjectType>('BUILD')
  const [busy, setBusy] = useState(false)

  const [typed, setTyped] = useState('')
  const tw = useRef({ pi: 0, ci: 0, del: false, hold: 0 })
  useEffect(() => {
    const timer = window.setInterval(() => {
      const s = tw.current
      if (s.hold > 0) { s.hold -= 1; return }
      const phrase = PHRASES[s.pi]
      if (!s.del) {
        s.ci += 1
        if (s.ci >= phrase.length) { s.del = true; s.hold = 34 }
      } else {
        s.ci -= 2
        if (s.ci <= 0) { s.ci = 0; s.del = false; s.pi = (s.pi + 1) % PHRASES.length; s.hold = 6 }
      }
      setTyped(phrase.slice(0, Math.max(0, s.ci)))
    }, 55)
    return () => window.clearInterval(timer)
  }, [])

  const build = async (text: string) => {
    const t = text.trim()
    if (!t || busy) return
    if (!isAuthenticated) {
      sessionStorage.setItem('kiln_pending_prompt', t)
      sessionStorage.setItem('kiln_pending_mode', mode)
      nav('/signup')
      return
    }
    setBusy(true)
    try {
      const project = await startProject(t, mode, selectedId ?? undefined)
      nav(`/project/${project.id}`)
    } finally {
      setBusy(false)
    }
  }
  const submit = (e?: FormEvent) => { e?.preventDefault(); void build(draft) }
  const goStart = () => nav(isAuthenticated ? '/dashboard' : '/signup')

  return (
    <div className="kl min-h-screen bg-[var(--bg)] text-[var(--ink)] font-['Geist',system-ui,sans-serif] animate-[kl-fade_.26s_ease_both] relative motion-reduce:animate-none">
      <div className="max-w-[1440px] mx-auto flex flex-col relative z-[1]">
        {/* NAV */}
        <nav className="h-[84px] shrink-0 flex items-center gap-10 px-16">
          <Link to="/" className="flex items-center gap-2.5">
            <span className="rounded-full bg-[conic-gradient(from_0deg,#FF5B2E,#FFB23E,#5B4CF5,#FF5B2E)] animate-[kl-spin_7s_linear_infinite] inline-flex items-center justify-center shrink-0" style={{ width: 30, height: 30 }}><span style={{ width: 12, height: 12, borderRadius: '50%', background: 'var(--bg)' }} /></span>
            <b className="font-['Bricolage_Grotesque',sans-serif] font-extrabold text-[23px] tracking-[-0.03em]">Kiln</b>
          </Link>
          <div className="flex gap-7 text-[15px]">
            <a href="#how" className="font-medium">Product</a>
            <Link to="/templates" className="font-medium">Templates</Link>
            <Link to="/pricing" className="font-medium">Pricing</Link>
            <a href="#docs" className="font-medium">Docs</a>
            <a href="#community" className="font-medium">Community</a>
          </div>
          <span className="grow" />
          <Link to="/auth" className="h-[42px] px-[18px] flex items-center rounded-[21px] text-sm font-medium">Log in</Link>
          <button className="h-11 px-5 inline-flex items-center gap-2 rounded-[22px] bg-[var(--ink)] text-[var(--bg)] text-sm font-semibold border-none transition-[transform,box-shadow] duration-300 ease-[cubic-bezier(.2,.8,.2,1)] hover:-translate-y-1 hover:shadow-[0_22px_40px_-18px_rgba(22,21,26,.28)]" onClick={goStart}>Start building <Arrow /></button>
        </nav>

        {/* HERO */}
        <section className="relative pt-[88px] px-6 pb-[72px] flex flex-col items-center gap-7">
          <div className="absolute rounded-2xl shadow-[0_18px_36px_-20px_rgba(22,21,26,.3)] max-[1100px]:hidden left-[max(24px,8%)] top-[150px] w-[170px] p-3.5 bg-white border border-[var(--line)] flex flex-col gap-2 animate-[kl-floaty-left_6s_ease-in-out_infinite]">
            <small className="text-[11px] font-semibold text-[var(--faint)]">Edited</small>
            <span className="font-['JetBrains_Mono',monospace] text-xs px-2 py-1 rounded-md self-start" style={{ background: '#F1EEFF', color: '#3D30C9' }}>Hero.tsx</span>
            <span className="font-['JetBrains_Mono',monospace] text-xs px-2 py-1 rounded-md self-start" style={{ background: '#FFEDE6', color: '#B23A12' }}>auth.ts</span>
          </div>
          <div className="absolute rounded-2xl max-[1100px]:hidden right-[max(24px,8%)] top-[120px] w-[200px] p-3.5 bg-[var(--ink)] text-[var(--bg)] flex items-center gap-2.5 animate-[kl-floaty_6s_ease-in-out_infinite] shadow-[0_18px_36px_-20px_rgba(22,21,26,.5)]" style={{ animationDelay: '-3s' }}>
            <span className="w-[9px] h-[9px] rounded-full bg-[#3DD68C] animate-[kl-pulse_1.6s_infinite] shrink-0" />
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <span style={{ fontSize: 13, fontWeight: 600 }}>Deployed</span>
              <span className="font-['JetBrains_Mono',monospace] text-xs px-2 py-1 rounded-md self-start" style={{ padding: 0, background: 'transparent', color: '#B9B6AE', fontSize: 11 }}>yourapp.kiln.app</span>
            </div>
          </div>

          <a href="#how" className="flex items-center gap-2.5 h-9 px-1.5 rounded-[18px] bg-white border border-[var(--line-2)] text-[13px] text-[var(--ink)] kl-rise"><span className="h-[26px] px-2.5 rounded-[13px] bg-[var(--ink)] text-[var(--bg)] font-semibold flex items-center">New</span><span>Plan mode — think it through before you build</span><Arrow s={14} /></a>
          <h1 className="m-0 text-center font-['Bricolage_Grotesque',sans-serif] font-extrabold text-[clamp(48px,7vw,96px)] leading-[.98] tracking-[-0.045em] kl-rise kl-d1">Describe it.<br /><span className="font-['Instrument_Serif',serif] italic font-normal tracking-[-0.02em] text-[var(--accent)]">Kiln</span> builds it.</h1>
          <p className="m-0 max-w-[620px] text-center text-[19px] leading-[1.55] text-[var(--muted)] kl-rise kl-d2">Turn one sentence into a working full-stack app — interface, database, login and hosting — then shape it by simply talking to it.</p>

          <form className="w-[min(640px,100%)] mt-3 pt-3.5 px-[18px] pb-2.5 rounded-[26px] bg-white border border-[var(--line-2)] shadow-[0_30px_60px_-30px_rgba(22,21,26,.35),0_0_0_6px_rgba(255,91,46,.06)] flex flex-col gap-2.5 kl-rise kl-d3" onSubmit={submit}>
            <div className="relative min-h-12">
              <textarea
                className="w-full resize-none border-none outline-none bg-transparent font-[inherit] text-lg leading-[1.5] text-[var(--ink)] py-1 px-1.5"
                rows={1}
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) submit(e) }}
                aria-label="Describe your app"
              />
              {draft.length === 0 && (
                <div className="absolute left-1.5 top-1 pointer-events-none text-lg leading-[1.5] text-[var(--dim)]">Build {typed}<span className="inline-block w-0.5 h-5 ml-0.5 align-[-3px] bg-[var(--accent)] animate-[kl-blink_1s_steps(1)_infinite]" /></div>
              )}
            </div>
            <div className="flex items-center gap-2">
              <button type="button" className="w-10 h-10 rounded-[20px] flex items-center justify-center border border-[var(--line-2)] bg-white text-[var(--ink)] hover:bg-[#EEEBE4]" aria-label="Attach files">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>
              </button>
              {isAuthenticated && (
                <div className="flex gap-0.5 p-[3px] rounded-[20px] bg-[#F1EEE8]">
                  {MODES.map(([value, label]) => (
                    <button type="button" key={value} className={`h-[34px] px-3.5 border-none rounded-[17px] text-[13px] font-semibold ${mode === value ? 'bg-white text-[var(--ink)] shadow-[0_2px_6px_rgba(22,21,26,.12)]' : 'bg-transparent text-[var(--faint)]'}`} onClick={() => setMode(value)}>{label}</button>
                  ))}
                </div>
              )}
              <span className="grow" />
              <button type="button" className="w-10 h-10 rounded-[20px] flex items-center justify-center border-none bg-transparent text-[var(--muted)] hover:bg-[#EEEBE4]" aria-label="Voice input">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="9" y="2" width="6" height="12" rx="3" /><path d="M19 10v1a7 7 0 0 1-14 0v-1M12 18v4" /></svg>
              </button>
              <button type="submit" className="w-11 h-11 rounded-[22px] bg-[var(--accent)] text-white border-none flex items-center justify-center shadow-[0_8px_18px_-8px_var(--accent)]" aria-label="Start building" disabled={busy}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M12 19V5M5 12l7-7 7 7" /></svg>
              </button>
            </div>
          </form>

          <div className="flex gap-2 flex-wrap justify-center max-w-[760px] kl-rise kl-d4">
            {CHIPS.map((c) => (
              <button key={c.label} type="button" className="h-9 px-3.5 rounded-[18px] border border-[var(--line-2)] bg-white text-[var(--muted)] text-[13px] flex items-center gap-[7px] hover:bg-[#EEEBE4]" onClick={() => setDraft('Build ' + c.prompt)}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d={c.icon} /></svg>
                {c.label}
              </button>
            ))}
          </div>
        </section>

        {/* HOW IT WORKS */}
        <section id="how" className="pt-[110px] px-16 pb-10 flex flex-col gap-11 max-[900px]:pt-[70px] max-[900px]:px-6 max-[900px]:pb-10">
          <div className="flex items-end justify-between gap-10 flex-wrap">
            <h2 className="m-0 font-['Bricolage_Grotesque',sans-serif] font-extrabold text-[clamp(34px,4vw,56px)] leading-[1.02] tracking-[-0.04em] max-w-[640px]">From idea to live app in <span className="font-['Instrument_Serif',serif] italic font-normal tracking-[-0.02em] text-[var(--accent)]">three</span> moves.</h2>
            <p className="m-0 max-w-[380px] text-base leading-[1.6] text-[var(--muted)]">No setup, no boilerplate. You stay in the conversation — Kiln writes, runs and ships the code.</p>
          </div>
          <div className="grid grid-cols-3 gap-5 max-[900px]:grid-cols-1">
            <article className="p-7 rounded-[28px] bg-white border border-[var(--line)] flex flex-col gap-[22px] transition-[transform,box-shadow] duration-300 ease-[cubic-bezier(.2,.8,.2,1)] hover:-translate-y-1 hover:shadow-[0_22px_40px_-18px_rgba(22,21,26,.28)]">
              <div className="h-[190px] rounded-[18px] bg-[var(--bg)] p-5 flex flex-col gap-2.5" style={{ justifyContent: 'flex-end' }}>
                <div style={{ alignSelf: 'flex-end', maxWidth: '80%', padding: '10px 14px', borderRadius: '16px 16px 4px 16px', background: '#16151A', color: '#F6F4EF', fontSize: 13 }}>A booking app for my yoga studio</div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}><span className="rounded-full bg-[conic-gradient(from_0deg,#FF5B2E,#FFB23E,#5B4CF5,#FF5B2E)] animate-[kl-spin_7s_linear_infinite] inline-flex items-center justify-center shrink-0" style={{ width: 22, height: 22 }} /><span style={{ height: 10, width: 140, borderRadius: 5, background: '#E3DFD6' }} /></div>
                <span style={{ height: 10, width: 200, borderRadius: 5, background: '#E3DFD6', marginLeft: 30 }} />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <span className="font-['JetBrains_Mono',monospace] text-xs text-[var(--accent)] font-medium">01 — DESCRIBE</span>
                <span className="font-['Bricolage_Grotesque',sans-serif] text-2xl font-bold tracking-[-0.02em]">Say what you want</span>
                <span className="text-[15px] leading-[1.55] text-[var(--muted)]">Type, talk or drop a screenshot. Plan mode maps out pages and data first.</span>
              </div>
            </article>
            <article className="p-7 rounded-[28px] bg-white border border-[var(--line)] flex flex-col gap-[22px] transition-[transform,box-shadow] duration-300 ease-[cubic-bezier(.2,.8,.2,1)] hover:-translate-y-1 hover:shadow-[0_22px_40px_-18px_rgba(22,21,26,.28)]">
              <div className="h-[190px] rounded-[18px] bg-[var(--bg)] p-5 flex flex-col gap-2.5" style={{ position: 'relative' }}>
                <span style={{ height: 14, width: 120, borderRadius: 5, background: '#16151A' }} />
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0,1fr))', gap: 10 }}>
                  <div style={{ height: 70, borderRadius: 10, background: '#fff', border: '2px solid #5B4CF5', position: 'relative' }}>
                    <span style={{ position: 'absolute', top: -11, left: 8, fontSize: 10, fontWeight: 600, padding: '2px 6px', borderRadius: 4, background: '#5B4CF5', color: '#fff' }}>Card</span>
                  </div>
                  <div style={{ height: 70, borderRadius: 10, background: '#fff', border: '1px solid #E3DFD6' }} />
                </div>
                <span style={{ height: 30, width: 110, borderRadius: 15, background: '#FF5B2E' }} />
                <svg width="22" height="22" viewBox="0 0 24 24" fill="#16151A" style={{ position: 'absolute', left: 120, top: 92 }}><path d="M4 3l15 7-6.5 2L10 19z" /></svg>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <span className="font-['JetBrains_Mono',monospace] text-xs text-[var(--accent)] font-medium">02 — REFINE</span>
                <span className="font-['Bricolage_Grotesque',sans-serif] text-2xl font-bold tracking-[-0.02em]">Point, click, tweak</span>
                <span className="text-[15px] leading-[1.55] text-[var(--muted)]">Select any element in the preview and ask for a change — or edit the code yourself.</span>
              </div>
            </article>
            <article className="p-7 rounded-[28px] bg-[var(--ink)] text-[var(--bg)] border border-[var(--line)] flex flex-col gap-[22px] transition-[transform,box-shadow] duration-300 ease-[cubic-bezier(.2,.8,.2,1)] hover:-translate-y-1 hover:shadow-[0_22px_40px_-18px_rgba(22,21,26,.28)]">
              <div className="h-[190px] rounded-[18px] bg-[var(--bg)] p-5 flex flex-col gap-2.5" style={{ background: '#232229', justifyContent: 'center', gap: 14 }}>
                <div style={{ height: 40, borderRadius: 12, background: '#2E2D35', display: 'flex', alignItems: 'center', gap: 10, padding: '0 14px' }}>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#B9B6AE" strokeWidth="2.2" strokeLinecap="round"><rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></svg>
                  <span style={{ fontFamily: "'JetBrains Mono', monospace", fontSize: 13, color: '#E6E3DC' }}>yogaflow.kiln.app</span>
                  <span style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: '#3DD68C' }}><span className="w-[9px] h-[9px] rounded-full bg-[#3DD68C] animate-[kl-pulse_1.6s_infinite] shrink-0" style={{ width: 7, height: 7 }} />Live</span>
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                  <span style={{ fontSize: 12, padding: '5px 10px', borderRadius: 8, background: '#2E2D35', color: '#E6E3DC' }}>Custom domain</span>
                  <span style={{ fontSize: 12, padding: '5px 10px', borderRadius: 8, background: '#2E2D35', color: '#E6E3DC' }}>GitHub sync</span>
                </div>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <span className="font-['JetBrains_Mono',monospace] text-xs text-[var(--accent)] font-medium" style={{ color: '#FF9A74' }}>03 — SHIP</span>
                <span className="font-['Bricolage_Grotesque',sans-serif] text-2xl font-bold tracking-[-0.02em]">Go live in one click</span>
                <span className="text-[15px] leading-[1.55] text-[#C9C6BE]">Hosting, SSL and a shareable URL are built in. Your code is always yours.</span>
              </div>
            </article>
          </div>
        </section>

        {/* TEMPLATES MARQUEE */}
        <section id="templates" className="pt-[70px] pb-10 flex flex-col gap-8">
          <div className="flex items-center justify-between px-16 gap-4 flex-wrap">
            <h2 className="m-0 font-['Bricolage_Grotesque',sans-serif] font-extrabold text-[clamp(34px,4vw,56px)] leading-[1.02] tracking-[-0.04em] max-w-[640px]" style={{ fontSize: 'clamp(30px,3.4vw,44px)' }}>Start from something <span className="font-['Instrument_Serif',serif] italic font-normal tracking-[-0.02em] text-[var(--accent)]">beautiful</span></h2>
            <Link to="/templates" style={{ fontSize: 15, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6 }}>Browse templates <Arrow /></Link>
          </div>
          <div className="overflow-hidden">
            <div className="flex gap-5 w-max pt-2.5 pb-6 animate-[kl-marquee_46s_linear_infinite] hover:[animation-play-state:paused]">
              {TEMPLATES.concat(TEMPLATES).map((t, i) => (
                <div className="w-[300px] shrink-0 rounded-[22px] bg-white border border-[var(--line)] p-3 flex flex-col gap-3 transition-[transform,box-shadow] duration-300 ease-[cubic-bezier(.2,.8,.2,1)] hover:-translate-y-1 hover:shadow-[0_22px_40px_-18px_rgba(22,21,26,.28)]" key={i}>
                  <div className="h-44 rounded-[14px] p-4 flex flex-col gap-2.5" style={{ background: t.bg }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <span style={{ height: 8, width: 46, borderRadius: 4, background: t.fg, opacity: .8 }} />
                      <span style={{ height: 18, width: 50, borderRadius: 9, background: t.ac }} />
                    </div>
                    <span style={{ height: 18, width: 170, borderRadius: 6, background: t.fg }} />
                    <span style={{ height: 8, width: 120, borderRadius: 4, background: t.fg, opacity: .45 }} />
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0,1fr))', gap: 8, marginTop: 'auto' }}>
                      <span style={{ height: 44, borderRadius: 8, background: t.card }} />
                      <span style={{ height: 44, borderRadius: 8, background: t.card }} />
                      <span style={{ height: 44, borderRadius: 8, background: t.card }} />
                    </div>
                  </div>
                  <div className="flex justify-between items-center px-1 pb-1"><b className="text-[15px] font-semibold">{t.name}</b><small className="text-xs text-[var(--faint)]">{t.kind}</small></div>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* CTA */}
        <section className="mt-[30px] mx-6 py-[72px] px-16 rounded-[36px] bg-[var(--ink)] text-[var(--bg)] flex items-center justify-between gap-10 relative overflow-hidden flex-wrap">
          <span className="rounded-full bg-[conic-gradient(from_0deg,#FF5B2E,#FFB23E,#5B4CF5,#FF5B2E)] animate-[kl-spin_7s_linear_infinite] inline-flex items-center justify-center shrink-0 absolute right-[-80px] top-[-80px] w-[260px] h-[260px] opacity-[.22]" />
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14, position: 'relative' }}>
            <h2 className="m-0 font-['Bricolage_Grotesque',sans-serif] font-extrabold text-[clamp(34px,4vw,52px)] leading-[1.02] tracking-[-0.04em]">Your next app is<br />one <span className="font-['Instrument_Serif',serif] italic font-normal tracking-[-0.02em] text-[#FF9A74]">sentence</span> away.</h2>
            <span className="text-[17px] text-[#C9C6BE]">Free to start. No credit card.</span>
          </div>
          <button className="relative h-14 px-7 rounded-[28px] bg-[var(--bg)] text-[var(--ink)] text-base font-semibold inline-flex items-center gap-2.5 border-none transition-[transform,box-shadow] duration-300 ease-[cubic-bezier(.2,.8,.2,1)] hover:-translate-y-1 hover:shadow-[0_22px_40px_-18px_rgba(22,21,26,.28)]" onClick={goStart}>Start building free <Arrow s={16} /></button>
        </section>

        <footer className="h-24 shrink-0 flex items-center justify-between px-16 text-[13px] text-[var(--faint)] flex-wrap gap-3">
          <span>© 2026 Kiln</span>
          <div className="flex gap-6">
            <a href="#terms" className="text-[var(--faint)]">Terms</a><a href="#privacy" className="text-[var(--faint)]">Privacy</a><a href="#security" className="text-[var(--faint)]">Security</a><a href="#status" className="text-[var(--faint)]">Status</a>
          </div>
        </footer>
      </div>
    </div>
  )
}
