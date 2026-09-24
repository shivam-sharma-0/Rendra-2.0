import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { projectApi, workspaceApi, type Project } from '../api/projects'
import { useAuth } from '../auth/AuthContext'
import { useTheme } from '../theme/ThemeProvider'
import CreateWorkspaceModal from './CreateWorkspaceModal'
import Logo from './Logo'
import ThemeToggle from './ThemeToggle'

/**
 * Left rail — Lovable-shaped: a clickable workspace switcher (opens a workspace
 * popover), primary nav, a scrollable Recents list, an upgrade card, and a clickable
 * user row (opens an account popover). Styled with Code·Pal's own tokens.
 */

/** Close a popover on outside-click / Escape. */
function useOutside(open: boolean, onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose()
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, onClose])
  return ref
}

const RAIL = 52 // collapsed icon-rail width (px) — ~20% narrower than before
const MIN_EXPANDED = 180 // narrowest usable expanded width
const COLLAPSE_AT = 160 // drag below this → collapse
const maxWidth = () => (typeof window === 'undefined' ? 260 : Math.round(window.innerWidth * 0.15))

export default function Sidebar() {
  const { user, signOut } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [recents, setRecents] = useState<Project[] | null>(null)
  const [wsOpen, setWsOpen] = useState(false)
  const [userOpen, setUserOpen] = useState(false)
  const [newWsOpen, setNewWsOpen] = useState(false)
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem('cp_sb_collapsed') === '1')
  const [width, setWidth] = useState(() => Number(localStorage.getItem('cp_sb_w')) || 264)
  const [resizing, setResizing] = useState(false)
  const dragging = useRef(false)

  const wsRef = useOutside(wsOpen, () => setWsOpen(false))
  const userRef = useOutside(userOpen, () => setUserOpen(false))

  useEffect(() => localStorage.setItem('cp_sb_collapsed', collapsed ? '1' : '0'), [collapsed])
  useEffect(() => localStorage.setItem('cp_sb_w', String(width)), [width])

  // Load the most-recent projects (all types) for the Recents list.
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const workspaces = await workspaceApi.list()
        const ws = workspaces.find((w) => w.personal) ?? workspaces[0]
        if (cancelled || !ws) return
        const page = await projectApi.list(ws.id, { size: 8 })
        if (!cancelled) setRecents(page.content)
      } catch {
        if (!cancelled) setRecents([])
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  // Drag-to-resize: clamp expanded width to [MIN_EXPANDED, 30vw]; below COLLAPSE_AT → rail.
  useEffect(() => {
    function onMove(e: MouseEvent) {
      if (!dragging.current) return
      const x = e.clientX
      if (x < COLLAPSE_AT) {
        setCollapsed(true)
      } else {
        setCollapsed(false)
        setWidth(Math.min(Math.max(x, MIN_EXPANDED), maxWidth()))
      }
    }
    function onUp() {
      dragging.current = false
      setResizing(false)
      document.body.style.userSelect = ''
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
    return () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
  }, [])

  function startDrag() {
    dragging.current = true
    setResizing(true)
    document.body.style.userSelect = 'none'
  }

  const initial = (user?.name?.[0] ?? user?.email?.[0] ?? 'U').toUpperCase()
  const name = firstName(user)
  const asideWidth = collapsed ? RAIL : Math.min(width, maxWidth())

  const workspaceMenu = (
    <>
      <div className="flex items-center gap-2.5 p-3">
        <Avatar initial={initial} className="h-9 w-9 rounded-lg text-sm" />
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-text">{name}'s workspace</div>
          <div className="text-[11px] text-muted">Free Plan · 1 member</div>
        </div>
      </div>
      <Divider />
      <div className="space-y-2 p-3">
        <OutlineBtn icon={<IconInvite />} label="Invite members" onClick={() => { setWsOpen(false); navigate('/settings/workspace') }} />
        <OutlineBtn icon={<IconSettings />} label="Settings" onClick={() => { setWsOpen(false); navigate('/settings/workspace') }} />
      </div>
      <Divider />
      <div className="px-3 py-2.5">
        <div className="flex items-center justify-between text-[13px]">
          <span className="font-medium text-text">Credits</span>
          <button className="inline-flex items-center gap-1 font-semibold text-accent hover:underline">
            Upgrade <ChevronRight />
          </button>
        </div>
        <div
          className="mt-2 h-2 w-full overflow-hidden rounded-full border border-border"
          style={{
            backgroundImage:
              'repeating-linear-gradient(45deg, var(--border) 0, var(--border) 4px, transparent 4px, transparent 8px)',
          }}
        />
        <div className="mt-2 flex items-center gap-1.5 text-[11px] text-muted">
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: 'var(--faint)' }} />
          Daily credits reset at midnight UTC
        </div>
      </div>
      <Divider />
      <div className="p-1.5">
        <MenuItem icon={<IconPlus />} label="New workspace" onClick={() => { setWsOpen(false); setNewWsOpen(true) }} />
      </div>
      <Divider />
      <button className="flex w-full items-center justify-between px-3 py-2.5 text-[13px] transition hover:bg-black/[0.03] dark:hover:bg-white/[0.04]">
        <span className="inline-flex items-center gap-2 font-medium text-text">
          <span className="text-spark"><IconBolt /></span> Turn Pro
        </span>
        <span
          className="rounded-md px-2 py-0.5 text-[11px] font-semibold text-white"
          style={{ background: 'linear-gradient(140deg, var(--spark), var(--spark-hover))' }}
        >
          Upgrade
        </span>
      </button>
    </>
  )

  const accountMenu = (
    <>
      <div className="flex items-center gap-2.5 p-3">
        <Avatar initial={initial} className="h-9 w-9 rounded-full text-sm" />
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-text">{name}</div>
          <div className="truncate text-[11px] text-muted">{user?.email}</div>
        </div>
      </div>
      <Divider />
      <div className="p-1.5">
        <MenuItem icon={<IconProfile />} label="Profile" />
        <MenuItem icon={<IconInbox />} label="Inbox" right={<span className="h-1.5 w-1.5 rounded-full" style={{ background: 'var(--spark)' }} />} />
        <MenuItem icon={<IconSettings />} label="Settings" right={<kbd className="text-[11px] text-faint">Ctrl .</kbd>} />
        <AppearanceItem />
      </div>
      <Divider />
      <div className="p-1.5">
        <MenuItem icon={<IconSupport />} label="Support" right={<ChevronRight />} />
        <MenuItem icon={<IconDocs />} label="Documentation" right={<ChevronRight />} />
        <MenuItem icon={<IconCommunity />} label="Community" />
      </div>
      <Divider />
      <div className="p-1.5">
        <MenuItem icon={<IconDownload />} label="Download apps" />
        <MenuItem icon={<IconHome />} label="Homepage" />
      </div>
      <Divider />
      <div className="p-1.5">
        <button
          onClick={signOut}
          className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] font-medium text-danger transition hover:bg-[color:var(--danger)]/10"
        >
          <span className="grid h-[18px] w-[18px] place-items-center"><IconSignOut /></span>
          Log out
        </button>
      </div>
    </>
  )

  return (
    <>
    <aside
      style={{ width: asideWidth }}
      className={`surface sticky top-0 z-30 flex h-screen shrink-0 flex-col border-y-0 border-l-0 ${resizing ? '' : 'transition-[width] duration-200'}`}
    >
      {/* Brand + collapse toggle */}
      {collapsed ? (
        <div className="flex flex-col items-center gap-2 px-2 pb-2 pt-4">
          <Logo showWordmark={false} />
          <RailBtn label="Expand" onClick={() => setCollapsed(false)}><ChevronRight /></RailBtn>
        </div>
      ) : (
        <div className="flex items-center justify-between px-4 pb-2 pt-4">
          <Logo showWordmark={false} />
          <div className="flex items-center gap-1.5">
            <button
              onClick={() => setCollapsed(true)}
              title="Collapse sidebar"
              className="grid h-7 w-7 place-items-center rounded-md text-muted transition hover:bg-black/[0.04] hover:text-text dark:hover:bg-white/[0.05]"
            >
              <ChevronLeft />
            </button>
            <ThemeToggle />
          </div>
        </div>
      )}

      {/* Workspace switcher + popover */}
      <div ref={wsRef} className={`relative ${collapsed ? 'flex justify-center px-2 pb-2' : 'px-3 pb-2 pt-1'}`}>
        {collapsed ? (
          <button
            onClick={() => setWsOpen((v) => !v)}
            aria-haspopup="menu"
            title={`${name}'s workspace`}
            className="grid h-8 w-8 place-items-center rounded-lg transition hover:bg-black/[0.04] dark:hover:bg-white/[0.05]"
          >
            <Avatar initial={initial} className="h-5 w-5 rounded-md text-[10px]" />
          </button>
        ) : (
          <button
            onClick={() => setWsOpen((v) => !v)}
            aria-haspopup="menu"
            aria-expanded={wsOpen}
            className="flex w-full items-center gap-2 rounded-lg border border-border px-2 py-1.5 text-left transition hover:bg-black/[0.03] dark:hover:bg-white/[0.04]"
          >
            <Avatar initial={initial} className="h-5 w-5 rounded text-[10px]" />
            <span className="flex-1 truncate text-[12px] font-semibold text-text">{name}'s workspace</span>
            <Chevron open={wsOpen} />
          </button>
        )}
        {wsOpen && (
          <Popover className={collapsed ? 'left-full top-2 ml-2 w-64' : 'left-3 right-3 top-full mt-1 min-w-[16rem]'}>{workspaceMenu}</Popover>
        )}
      </div>

      {/* Primary nav */}
      <nav className={collapsed ? 'flex flex-col items-center gap-1 px-2' : 'space-y-0.5 px-3'}>
        <NavItem icon={<IconNew />} label="New" collapsed={collapsed} onClick={() => navigate('/dashboard')} />
        <NavItem icon={<IconSearch />} label="Search" collapsed={collapsed} />
        <NavItem icon={<IconConnectors />} label="Connectors" collapsed={collapsed} />
        <div className="h-2" />
        <NavItem icon={<IconChats />} label="Chats" collapsed={collapsed} active={pathname === '/chats'} onClick={() => navigate('/chats')} />
        <NavItem icon={<IconProjects />} label="Projects" collapsed={collapsed} active={pathname === '/projects'} onClick={() => navigate('/projects')} />
      </nav>

      {/* Recents (hidden when collapsed) */}
      {collapsed ? (
        <div className="flex-1" />
      ) : (
        <div className="mt-4 flex min-h-0 flex-1 flex-col px-3">
          <div className="px-2 pb-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted">Recents</div>
          <div className="-mr-1 flex-1 space-y-0.5 overflow-y-auto pr-1">
            {recents === null ? (
              <div className="px-2.5 py-1.5 text-[12px] text-faint">Loading…</div>
            ) : recents.length === 0 ? (
              <div className="px-2.5 py-1.5 text-[12px] text-faint">No recents yet</div>
            ) : (
              recents.map((p) => (
                <button
                  key={p.id}
                  onClick={() => navigate(`/project/${p.id}`)}
                  title={p.name}
                  className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-left text-[13px] text-text transition hover:bg-black/[0.04] dark:hover:bg-white/[0.05]"
                >
                  <span className="text-muted">{p.type === 'CHAT' ? <IconRecentChat /> : <IconRecentProject />}</span>
                  <span className="truncate">{p.name}</span>
                </button>
              ))
            )}
          </div>
        </div>
      )}

      {/* Upgrade card (hidden when collapsed) */}
      {!collapsed && (
        <div className="px-3 pb-3 pt-2">
          <div className="flex items-center gap-3 rounded-xl border border-border bg-[color:var(--card)] p-3">
            <span
              className="grid h-8 w-8 shrink-0 place-items-center rounded-lg text-white"
              style={{ background: 'linear-gradient(140deg, var(--spark), var(--spark-hover))' }}
            >
              <IconBolt />
            </span>
            <div className="min-w-0 flex-1">
              <div className="text-[13px] font-semibold text-text">Upgrade to Pro</div>
              <div className="truncate text-[11px] text-muted">Unlock more features</div>
            </div>
          </div>
        </div>
      )}

      {/* User row + account popover */}
      <div ref={userRef} className={`relative border-t border-border ${collapsed ? 'flex justify-center py-3' : ''}`}>
        {collapsed ? (
          <button
            onClick={() => setUserOpen((v) => !v)}
            aria-haspopup="menu"
            title={name}
            className="relative grid h-8 w-8 place-items-center rounded-lg transition hover:bg-black/[0.04] dark:hover:bg-white/[0.05]"
          >
            <Avatar initial={initial} className="h-6 w-6 rounded-full text-[11px]" />
            <span className="absolute right-1 top-1 h-2 w-2 rounded-full ring-2 ring-[color:var(--surface)]" style={{ background: 'var(--spark)' }} />
          </button>
        ) : (
          <button
            onClick={() => setUserOpen((v) => !v)}
            aria-haspopup="menu"
            aria-expanded={userOpen}
            className="flex w-full items-center gap-2.5 px-3 py-3 text-left transition hover:bg-black/[0.03] dark:hover:bg-white/[0.04]"
          >
            <span className="relative">
              <Avatar initial={initial} className="h-8 w-8 rounded-full text-[12px]" />
              <span className="absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full ring-2 ring-[color:var(--surface)]" style={{ background: 'var(--spark)' }} />
            </span>
            <div className="min-w-0 flex-1">
              <div className="truncate text-[13px] font-semibold text-text">{name}</div>
              <div className="truncate text-[11px] text-muted">{user?.email}</div>
            </div>
            <Chevron open={userOpen} />
          </button>
        )}
        {userOpen && (
          <Popover className={collapsed ? 'left-full bottom-2 ml-2 w-64' : 'left-3 right-3 bottom-full mb-1 min-w-[16rem]'}>{accountMenu}</Popover>
        )}
      </div>

      {/* Drag-to-resize handle: a wide invisible grab area with a thin dark-red
          line that's strongest at the vertical middle and fades toward top/bottom. */}
      <div
        onMouseDown={startDrag}
        title="Drag to resize"
        className="group absolute right-0 top-0 z-40 flex h-full w-2 cursor-col-resize justify-end"
      >
        <span
          className={`h-full w-px transition-all duration-150 group-hover:w-0.5 group-hover:opacity-100 ${resizing ? 'w-0.5 opacity-100' : 'opacity-0'}`}
          style={{ background: 'linear-gradient(to bottom, transparent 0%, #8b0000 50%, transparent 100%)' }}
        />
      </div>
    </aside>

    <CreateWorkspaceModal
      open={newWsOpen}
      onClose={() => setNewWsOpen(false)}
      onCreated={(ws) => { setNewWsOpen(false); navigate(`/settings/workspace?id=${ws.id}`) }}
    />
    </>
  )
}

function RailBtn({ children, label, onClick }: { children: ReactNode; label: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      title={label}
      className="grid h-7 w-7 place-items-center rounded-md text-muted transition hover:bg-black/[0.04] hover:text-text dark:hover:bg-white/[0.05]"
    >
      {children}
    </button>
  )
}

/* ── Building blocks ──────────────────────────────────────────────────────── */

function Popover({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div
      role="menu"
      className={`card animate-pop-in absolute z-50 overflow-hidden rounded-2xl p-0 ${className}`}
    >
      {children}
    </div>
  )
}

function OutlineBtn({ icon, label, onClick }: { icon: ReactNode; label: string; onClick?: () => void }) {
  return (
    <button onClick={onClick} className="flex w-full items-center justify-center gap-2 rounded-lg border border-border py-2 text-[13px] font-medium text-text transition hover:bg-black/[0.03] dark:hover:bg-white/[0.04]">
      <span className="grid h-4 w-4 place-items-center text-muted">{icon}</span>
      {label}
    </button>
  )
}

function MenuItem({ icon, label, right, onClick }: { icon: ReactNode; label: string; right?: ReactNode; onClick?: () => void }) {
  return (
    <button onClick={onClick} className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] font-medium text-text transition hover:bg-black/[0.03] dark:hover:bg-white/[0.04]">
      <span className="grid h-[18px] w-[18px] place-items-center text-muted">{icon}</span>
      <span className="flex-1 truncate">{label}</span>
      {right}
    </button>
  )
}

/** Appearance row — toggles the theme (functional). */
function AppearanceItem() {
  const { theme, toggle } = useTheme()
  return (
    <button
      onClick={toggle}
      className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] font-medium text-text transition hover:bg-black/[0.03] dark:hover:bg-white/[0.04]"
    >
      <span className="grid h-[18px] w-[18px] place-items-center text-muted"><IconAppearance /></span>
      <span className="flex-1 truncate">Appearance</span>
      <span className="text-[11px] capitalize text-faint">{theme}</span>
    </button>
  )
}

function Avatar({ initial, className = '' }: { initial: string; className?: string }) {
  return (
    <span
      className={`grid shrink-0 place-items-center border border-border bg-[color:var(--border-strong)] font-bold text-text ${className}`}
    >
      {initial}
    </span>
  )
}

function Divider() {
  return <div className="h-px bg-[color:var(--border)]" />
}

function NavItem({ icon, label, active, collapsed, onClick }: { icon: ReactNode; label: string; active?: boolean; collapsed?: boolean; onClick?: () => void }) {
  const tone = active
    ? 'bg-[color:var(--accent-subtle)] text-accent'
    : 'text-text hover:bg-black/[0.04] dark:hover:bg-white/[0.05]'
  if (collapsed) {
    return (
      <button onClick={onClick} title={label} className={`grid h-8 w-8 place-items-center rounded-lg transition ${tone}`}>
        <span className="grid h-[18px] w-[18px] place-items-center">{icon}</span>
      </button>
    )
  }
  return (
    <button onClick={onClick} className={`flex w-full items-center gap-3 rounded-lg px-2.5 py-2 text-left text-[13px] font-medium transition ${tone}`}>
      <span className="grid h-[18px] w-[18px] place-items-center">{icon}</span>
      {label}
    </button>
  )
}

function firstName(user: { name?: string | null; email?: string } | null | undefined) {
  if (!user) return 'You'
  return user.name?.split(' ')[0] ?? user.email?.split('@')[0] ?? 'You'
}

/* ── Icons (stroke = currentColor) ───────────────────────────────────────── */
const svg = { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const }

const IconNew = () => (<svg {...svg}><path d="M12 20h9" /><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z" /></svg>)
const IconSearch = () => (<svg {...svg}><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>)
const IconConnectors = () => (<svg {...svg}><path d="M10 13a5 5 0 0 0 7 0l2-2a5 5 0 0 0-7-7l-1 1" /><path d="M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 0 0 7 7l1-1" /></svg>)
const IconChats = () => (<svg {...svg}><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8z" /></svg>)
const IconProjects = () => (<svg {...svg}><path d="m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z" /><path d="m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65" /><path d="m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65" /></svg>)
const IconRecentProject = () => (<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z" /><path d="m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65" /><path d="m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65" /></svg>)
const IconRecentChat = () => (<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8z" /></svg>)
const IconBolt = () => (<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M13 2 3 14h7l-1 8 10-12h-7z" /></svg>)
const IconSignOut = () => (<svg {...svg}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><path d="m16 17 5-5-5-5M21 12H9" /></svg>)
const IconInvite = () => (<svg {...svg}><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M19 8v6M22 11h-6" /></svg>)
const IconSettings = () => (<svg {...svg}><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" /></svg>)
const IconPlus = () => (<svg {...svg}><path d="M12 5v14M5 12h14" /></svg>)
const IconProfile = () => (<svg {...svg}><circle cx="12" cy="8" r="4" /><path d="M4 21v-1a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v1" /></svg>)
const IconInbox = () => (<svg {...svg}><path d="M22 12h-6l-2 3h-4l-2-3H2" /><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" /></svg>)
const IconAppearance = () => (<svg {...svg}><circle cx="12" cy="12" r="9" /><path d="M12 3v18" fill="currentColor" /><path d="M12 3a9 9 0 0 1 0 18z" fill="currentColor" stroke="none" /></svg>)
const IconSupport = () => (<svg {...svg}><circle cx="12" cy="12" r="9" /><path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3" /><path d="M12 17h.01" /></svg>)
const IconDocs = () => (<svg {...svg}><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" /></svg>)
const IconCommunity = () => (<svg {...svg}><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" /></svg>)
const IconDownload = () => (<svg {...svg}><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><path d="m7 10 5 5 5-5M12 15V3" /></svg>)
const IconHome = () => (<svg {...svg}><path d="m3 10 9-7 9 7v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /><path d="M9 21V12h6v9" /></svg>)
const ChevronRight = () => (<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m9 6 6 6-6 6" /></svg>)
const ChevronLeft = () => (<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m15 6-6 6 6 6" /></svg>)

function Chevron({ open }: { open?: boolean }) {
  return (
    <svg
      width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round"
      className={`text-faint transition-transform ${open ? 'rotate-180' : ''}`}
    >
      <path d="m6 9 6 6 6-6" />
    </svg>
  )
}
