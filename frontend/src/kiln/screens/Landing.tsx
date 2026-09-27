import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { startProject, type ProjectType } from '../../api/projects'
import './landing.css'

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

  // typewriter ghost placeholder (only while the field is empty)
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
    <div className="kl">
      <div className="kl-inner">
        {/* NAV */}
        <nav className="kl-nav">
          <Link to="/" className="kl-brand">
            <span className="kl-orb" style={{ width: 30, height: 30 }}><span style={{ width: 12, height: 12, borderRadius: '50%', background: 'var(--bg)' }} /></span>
            <b>Kiln</b>
          </Link>
          <div className="kl-navlinks">
            <a href="#how">Product</a>
            <Link to="/templates">Templates</Link>
            <Link to="/pricing">Pricing</Link>
            <a href="#docs">Docs</a>
            <a href="#community">Community</a>
          </div>
          <span className="kl-grow" />
          <Link to="/auth" className="kl-login">Log in</Link>
          <button className="kl-start kl-lift" onClick={goStart}>Start building <Arrow /></button>
        </nav>

        {/* HERO */}
        <section className="kl-hero">
          <div className="kl-float left">
            <small>Edited</small>
            <span className="kl-chip-mono" style={{ background: '#F1EEFF', color: '#3D30C9' }}>Hero.tsx</span>
            <span className="kl-chip-mono" style={{ background: '#FFEDE6', color: '#B23A12' }}>auth.ts</span>
          </div>
          <div className="kl-float right">
            <span className="kl-dot" />
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <span style={{ fontSize: 13, fontWeight: 600 }}>Deployed</span>
              <span className="kl-chip-mono" style={{ padding: 0, background: 'transparent', color: '#B9B6AE', fontSize: 11 }}>yourapp.kiln.app</span>
            </div>
          </div>

          <a href="#how" className="kl-pill kl-rise"><span className="tag">New</span><span>Plan mode — think it through before you build</span><Arrow s={14} /></a>
          <h1 className="kl-h1 kl-rise kl-d1">Describe it.<br /><span className="kl-em">Kiln</span> builds it.</h1>
          <p className="kl-sub kl-rise kl-d2">Turn one sentence into a working full-stack app — interface, database, login and hosting — then shape it by simply talking to it.</p>

          <form className="kl-prompt kl-rise kl-d3" onSubmit={submit}>
            <div className="kl-ta-wrap">
              <textarea
                rows={1}
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) submit(e) }}
                aria-label="Describe your app"
              />
              {draft.length === 0 && (
                <div className="kl-ghost">Build {typed}<span className="kl-caret" /></div>
              )}
            </div>
            <div className="kl-prow">
              <button type="button" className="kl-icon-btn bordered kl-hov" aria-label="Attach files">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>
              </button>
              {isAuthenticated && (
                <div className="kl-modes">
                  {MODES.map(([value, label]) => (
                    <button type="button" key={value} className={mode === value ? 'active' : ''} onClick={() => setMode(value)}>{label}</button>
                  ))}
                </div>
              )}
              <span className="kl-grow" />
              <button type="button" className="kl-icon-btn plain kl-hov" aria-label="Voice input">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="9" y="2" width="6" height="12" rx="3" /><path d="M19 10v1a7 7 0 0 1-14 0v-1M12 18v4" /></svg>
              </button>
              <button type="submit" className="kl-send" aria-label="Start building" disabled={busy}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M12 19V5M5 12l7-7 7 7" /></svg>
              </button>
            </div>
          </form>

          <div className="kl-chips kl-rise kl-d4">
            {CHIPS.map((c) => (
              <button key={c.label} type="button" className="kl-hov" onClick={() => setDraft('Build ' + c.prompt)}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d={c.icon} /></svg>
                {c.label}
              </button>
            ))}
          </div>
        </section>

        {/* HOW IT WORKS */}
        <section id="how" className="kl-how">
          <div className="kl-how-head">
            <h2 className="kl-h2">From idea to live app in <span className="kl-em">three</span> moves.</h2>
            <p className="kl-how-sub">No setup, no boilerplate. You stay in the conversation — Kiln writes, runs and ships the code.</p>
          </div>
          <div className="kl-how-grid">
            <article className="kl-hcard kl-lift">
              <div className="kl-hcard-visual" style={{ justifyContent: 'flex-end' }}>
                <div style={{ alignSelf: 'flex-end', maxWidth: '80%', padding: '10px 14px', borderRadius: '16px 16px 4px 16px', background: '#16151A', color: '#F6F4EF', fontSize: 13 }}>A booking app for my yoga studio</div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}><span className="kl-orb" style={{ width: 22, height: 22 }} /><span style={{ height: 10, width: 140, borderRadius: 5, background: '#E3DFD6' }} /></div>
                <span style={{ height: 10, width: 200, borderRadius: 5, background: '#E3DFD6', marginLeft: 30 }} />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <span className="kl-step-kicker">01 — DESCRIBE</span>
                <span className="kl-step-title">Say what you want</span>
                <span className="kl-step-text">Type, talk or drop a screenshot. Plan mode maps out pages and data first.</span>
              </div>
            </article>
            <article className="kl-hcard kl-lift">
              <div className="kl-hcard-visual" style={{ position: 'relative' }}>
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
                <span className="kl-step-kicker">02 — REFINE</span>
                <span className="kl-step-title">Point, click, tweak</span>
                <span className="kl-step-text">Select any element in the preview and ask for a change — or edit the code yourself.</span>
              </div>
            </article>
            <article className="kl-hcard dark kl-lift">
              <div className="kl-hcard-visual" style={{ background: '#232229', justifyContent: 'center', gap: 14 }}>
                <div style={{ height: 40, borderRadius: 12, background: '#2E2D35', display: 'flex', alignItems: 'center', gap: 10, padding: '0 14px' }}>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#B9B6AE" strokeWidth="2.2" strokeLinecap="round"><rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></svg>
                  <span style={{ fontFamily: "'JetBrains Mono', monospace", fontSize: 13, color: '#E6E3DC' }}>yogaflow.kiln.app</span>
                  <span style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: '#3DD68C' }}><span className="kl-dot" style={{ width: 7, height: 7 }} />Live</span>
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                  <span style={{ fontSize: 12, padding: '5px 10px', borderRadius: 8, background: '#2E2D35', color: '#E6E3DC' }}>Custom domain</span>
                  <span style={{ fontSize: 12, padding: '5px 10px', borderRadius: 8, background: '#2E2D35', color: '#E6E3DC' }}>GitHub sync</span>
                </div>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <span className="kl-step-kicker" style={{ color: '#FF9A74' }}>03 — SHIP</span>
                <span className="kl-step-title">Go live in one click</span>
                <span className="kl-step-text">Hosting, SSL and a shareable URL are built in. Your code is always yours.</span>
              </div>
            </article>
          </div>
        </section>

        {/* TEMPLATES MARQUEE */}
        <section id="templates" className="kl-tpl">
          <div className="kl-tpl-head">
            <h2 className="kl-h2" style={{ fontSize: 'clamp(30px,3.4vw,44px)' }}>Start from something <span className="kl-em">beautiful</span></h2>
            <Link to="/templates" style={{ fontSize: 15, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6 }}>Browse templates <Arrow /></Link>
          </div>
          <div className="kl-tpl-viewport">
            <div className="kl-track">
              {TEMPLATES.concat(TEMPLATES).map((t, i) => (
                <div className="kl-tcard kl-lift" key={i}>
                  <div className="kl-tcard-visual" style={{ background: t.bg }}>
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
                  <div className="kl-tcard-foot"><b>{t.name}</b><small>{t.kind}</small></div>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* CTA */}
        <section className="kl-cta">
          <span className="kl-orb" />
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14, position: 'relative' }}>
            <h2>Your next app is<br />one <span className="kl-em">sentence</span> away.</h2>
            <span className="kl-cta-note">Free to start. No credit card.</span>
          </div>
          <button className="kl-cta-btn kl-lift" onClick={goStart}>Start building free <Arrow s={16} /></button>
        </section>

        <footer className="kl-footer">
          <span>© 2026 Kiln</span>
          <div className="links">
            <a href="#terms">Terms</a><a href="#privacy">Privacy</a><a href="#security">Security</a><a href="#status">Status</a>
          </div>
        </footer>
      </div>
    </div>
  )
}
