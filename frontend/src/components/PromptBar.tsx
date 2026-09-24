import { useEffect, useRef, useState, type ChangeEvent, type KeyboardEvent, type ReactNode } from 'react'
import { useAuth } from '../auth/AuthContext'

type Props = {
  onSubmit: (prompt: string, mode: 'BUILD' | 'CHAT' | 'PLAN') => void
  placeholder?: string
  /** Called when a signed-out user tries to use a gated feature (e.g. modes). */
  onRequireLogin?: () => void
}

type Mode = 'Build' | 'Chat' | 'Plan'
const MODES: { name: Mode; desc: string; badge?: string }[] = [
  { name: 'Build', desc: 'Make changes directly' },
  { name: 'Chat', desc: 'Ask anything, explore ideas', badge: 'Free' },
  { name: 'Plan', desc: 'Detailed spec for complex builds' },
]

/** Center prompt composer: auto-growing textarea with a + menu (attach / add context), mode, mic, and send. */
export default function PromptBar({ onSubmit, placeholder = 'Describe what you want to build…', onRequireLogin }: Props) {
  const { isAuthenticated } = useAuth()
  const [value, setValue] = useState('')
  const ref = useRef<HTMLTextAreaElement>(null)
  const canSend = value.trim().length > 0

  // + menu
  const [menuOpen, setMenuOpen] = useState(false)
  const [view, setView] = useState<'root' | 'context'>('root')
  const menuRef = useRef<HTMLDivElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  // mode dropdown
  const [mode, setMode] = useState<Mode>('Build')
  const [modeOpen, setModeOpen] = useState(false)
  const modeRef = useRef<HTMLDivElement>(null)

  const closeMenu = () => {
    setMenuOpen(false)
    setView('root')
  }

  // Close the + menu on outside-click / Escape.
  useEffect(() => {
    if (!menuOpen) return
    const onDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) closeMenu()
    }
    const onKey = (e: KeyboardEvent | globalThis.KeyboardEvent) => e.key === 'Escape' && closeMenu()
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey as EventListener)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey as EventListener)
    }
  }, [menuOpen])

  // Close the mode dropdown on outside-click / Escape.
  useEffect(() => {
    if (!modeOpen) return
    const onDown = (e: MouseEvent) => {
      if (modeRef.current && !modeRef.current.contains(e.target as Node)) setModeOpen(false)
    }
    const onKey = (e: globalThis.KeyboardEvent) => e.key === 'Escape' && setModeOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [modeOpen])

  // Alt+P cycles through the modes (signed-in only).
  useEffect(() => {
    if (!isAuthenticated) return
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.altKey && (e.key === 'p' || e.key === 'P')) {
        e.preventDefault()
        setMode((m) => MODES[(MODES.findIndex((x) => x.name === m) + 1) % MODES.length].name)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [isAuthenticated])

  function autoGrow(e: ChangeEvent<HTMLTextAreaElement>) {
    setValue(e.target.value)
    const el = e.target
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 220)}px`
  }

  function submit() {
    const text = value.trim()
    if (!text) return
    onSubmit(text, mode.toUpperCase() as 'BUILD' | 'CHAT' | 'PLAN')
    setValue('')
    if (ref.current) ref.current.style.height = 'auto'
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }

  function openAttach() {
    fileRef.current?.click()
    closeMenu()
  }

  function onFilesChosen(e: ChangeEvent<HTMLInputElement>) {
    // TODO: wire real upload; for now just log the selection.
    const files = e.target.files
    if (files?.length) console.log('attached', Array.from(files).map((f) => f.name))
    e.target.value = ''
  }

  return (
    <div className="card rounded-2xl p-2.5 transition focus-within:border-[color:var(--accent)] focus-within:ring-2 focus-within:ring-[color:var(--ring)]">
      <textarea
        ref={ref}
        rows={1}
        value={value}
        onChange={autoGrow}
        onKeyDown={onKeyDown}
        placeholder={placeholder}
        className="max-h-[220px] w-full resize-none bg-transparent px-3 py-2.5 text-[15px] leading-relaxed text-text outline-none placeholder:text-muted/70"
      />

      <div className="flex items-center justify-between px-1 pb-0.5 pt-1">
        <div className="flex items-center gap-1.5">
          {/* + menu */}
          <div ref={menuRef} className="relative">
            <IconButton label="Add" onClick={() => (menuOpen ? closeMenu() : setMenuOpen(true))}>
              <path d="M12 5v14M5 12h14" strokeWidth="2" strokeLinecap="round" />
            </IconButton>

            {menuOpen && (
              <div className="card animate-pop-in absolute top-full left-0 z-50 mt-2 w-64 overflow-hidden rounded-2xl p-0">
                {view === 'root' ? (
                  <div className="p-1.5">
                    <MenuRow icon={<IconPaperclip />} label="Attach" onClick={openAttach} />
                    <MenuRow
                      icon={<IconAt />}
                      label="Add context"
                      onClick={() => setView('context')}
                      right={
                        <span className="flex items-center gap-1.5 text-muted">
                          <span className="text-[13px]">@</span>
                          <ChevronR />
                        </span>
                      }
                    />
                  </div>
                ) : (
                  <>
                    <button
                      type="button"
                      onClick={() => setView('root')}
                      className="flex w-full items-center gap-2 border-b border-border px-3 py-2.5 text-[13px] font-medium text-text transition hover:bg-black/[0.03] dark:hover:bg-white/[0.04]"
                    >
                      <span className="text-muted"><ChevronL /></span>
                      Context
                    </button>
                    <div className="p-1.5">
                      <MenuRow icon={<IconSkills />} label="Skills" right={<span className="flex items-center gap-2 text-faint"><kbd className="text-[11px]">/</kbd><ChevronR /></span>} />
                      <MenuRow icon={<IconGrid />} label="Projects" right={<ChevronR />} />
                      <MenuRow icon={<IconConnectors />} label="Connectors" right={<ChevronR />} />
                      <MenuRow icon={<IconSliders />} label="Manage connectors" />
                    </div>
                  </>
                )}
              </div>
            )}

            <input ref={fileRef} type="file" multiple onChange={onFilesChosen} className="hidden" />
          </div>

          {/* Mode dropdown */}
          <div ref={modeRef} className="relative">
            <button
              type="button"
              onClick={() => setModeOpen((v) => !v)}
              aria-haspopup="menu"
              aria-expanded={modeOpen}
              className="inline-flex items-center gap-1.5 rounded-full border border-[color:var(--border-strong)] px-3 py-1.5 text-[13px] font-semibold text-accent transition hover:bg-[color:var(--accent-subtle)]"
            >
              <span className="h-1.5 w-1.5 rounded-full" style={{ background: 'var(--accent)' }} />
              {mode}
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden className={`transition-transform ${modeOpen ? 'rotate-180' : ''}`}>
                <path d="m6 9 6 6 6-6" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>

            {modeOpen && (isAuthenticated ? (
              <div className="card animate-pop-in absolute top-full left-0 z-50 mt-2 w-64 overflow-hidden rounded-2xl p-1.5">
                {MODES.map((m) => (
                  <button
                    key={m.name}
                    type="button"
                    onClick={() => {
                      setMode(m.name)
                      setModeOpen(false)
                    }}
                    className="flex w-full items-start gap-2 rounded-lg px-2.5 py-2 text-left transition hover:bg-black/[0.03] dark:hover:bg-white/[0.04]"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2 text-[13px] font-semibold text-text">
                        {m.name}
                        {m.badge && (
                          <span className="inline-flex items-center gap-1 rounded-full border border-border px-1.5 py-0.5 text-[10px] font-medium text-muted">
                            <span className="h-1.5 w-1.5 rounded-full border border-current" /> {m.badge}
                          </span>
                        )}
                        {mode === m.name && <span className="ml-auto text-accent"><IconCheck /></span>}
                      </div>
                      <div className="text-[11px] text-muted">{m.desc}</div>
                    </div>
                  </button>
                ))}
                <div className="mt-1 flex items-center gap-1.5 border-t border-border px-2.5 pt-2 text-[11px] text-faint">
                  Switch modes with <Kbd>Alt</Kbd> <Kbd>P</Kbd>
                </div>
              </div>
            ) : (
              <div className="card animate-pop-in absolute top-full left-0 z-50 mt-2 w-64 overflow-hidden rounded-2xl p-4">
                <div className="text-[13px] font-semibold text-text">Unlock more features</div>
                <p className="mt-1 text-[12px] leading-relaxed text-muted">
                  Attach files and images, apply themes, connect integrations, and more by logging in.
                </p>
                <div className="mt-3 flex justify-end">
                  <button
                    type="button"
                    onClick={() => {
                      setModeOpen(false)
                      onRequireLogin?.()
                    }}
                    className="rounded-full bg-[color:var(--text)] px-4 py-1.5 text-[13px] font-semibold text-[color:var(--bg-solid)] transition hover:opacity-90"
                  >
                    Log in
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="flex items-center gap-1.5">
          <IconButton label="Voice input">
            <path d="M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </IconButton>
          <button
            type="button"
            onClick={submit}
            disabled={!canSend}
            aria-label="Send"
            className="grid h-10 w-10 place-items-center rounded-xl bg-[color:var(--spark)] text-[color:var(--spark-contrast)] transition enabled:hover:bg-[color:var(--spark-hover)] disabled:opacity-40"
            style={{ boxShadow: canSend ? 'var(--shadow-pop)' : 'none' }}
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden>
              <path d="M12 20V5M6 11l6-6 6 6" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  )
}

/* ── Menu building blocks ─────────────────────────────────────────────────── */

function MenuRow({ icon, label, right, onClick }: { icon: ReactNode; label: string; right?: ReactNode; onClick?: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] font-medium text-text transition hover:bg-black/[0.03] dark:hover:bg-white/[0.04]"
    >
      <span className="grid h-[18px] w-[18px] place-items-center text-muted">{icon}</span>
      <span className="flex-1 truncate">{label}</span>
      {right}
    </button>
  )
}

function IconButton({ label, children, onClick }: { label: string; children: ReactNode; onClick?: () => void }) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className="grid h-9 w-9 place-items-center rounded-full text-muted transition hover:bg-black/[0.05] hover:text-text dark:hover:bg-white/[0.06]"
    >
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden>
        {children}
      </svg>
    </button>
  )
}

/* ── Icons ─────────────────────────────────────────────────────────────────── */
const s = { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const }
const IconPaperclip = () => (<svg width="16" height="16" {...s}><path d="M21.44 11.05 12.25 20.24a5 5 0 0 1-7.07-7.07l9.19-9.19a3 3 0 0 1 4.24 4.24l-9.2 9.19a1 1 0 0 1-1.41-1.41l8.49-8.49" /></svg>)
const IconAt = () => (<svg width="16" height="16" {...s}><circle cx="12" cy="12" r="4" /><path d="M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-3.92 7.94" /></svg>)
const IconSkills = () => (<svg width="16" height="16" {...s}><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M7 9h4M7 13h7" /></svg>)
const IconGrid = () => (<svg width="16" height="16" {...s}><rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" /><rect x="3" y="14" width="7" height="7" rx="1.5" /><rect x="14" y="14" width="7" height="7" rx="1.5" /></svg>)
const IconConnectors = () => (<svg width="16" height="16" {...s}><path d="M10 13a5 5 0 0 0 7 0l2-2a5 5 0 0 0-7-7l-1 1" /><path d="M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 0 0 7 7l1-1" /></svg>)
const IconSliders = () => (<svg width="16" height="16" {...s}><path d="M4 6h10M18 6h2M4 12h2M10 12h10M4 18h7M15 18h5" /><circle cx="16" cy="6" r="2" /><circle cx="8" cy="12" r="2" /><circle cx="13" cy="18" r="2" /></svg>)
const ChevronR = () => (<svg width="13" height="13" {...s}><path d="m9 6 6 6-6 6" /></svg>)
const ChevronL = () => (<svg width="15" height="15" {...s}><path d="m15 6-6 6 6 6" /></svg>)
const IconCheck = () => (<svg width="15" height="15" {...s}><path d="M20 6 9 17l-5-5" /></svg>)

function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="rounded border border-border bg-[color:var(--surface)] px-1.5 py-0.5 text-[10px] font-medium text-muted">{children}</kbd>
}
