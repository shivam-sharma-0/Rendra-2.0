import { useMemo, useState, type ReactNode } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useWorkspace } from '../workspace/WorkspaceContext'
import { useProjects, useUnreadCount, useDeleteProject, useNotifications, useMarkAllNotificationsRead, useStartProject } from '../api/queries'
import { notificationApi } from '../api/projects'
import { Spinner } from '../components/ui/States'
import { relTime } from './parts'

/**
 * Authenticated app shell — sidebar (workspace switcher, nav, recents, Go Pro, account menu) +
 * top bar (context banner / search, notifications popup) + a rounded main surface for `children`.
 * Faithful to Figma "08 · Dashboard home"; shared by Dashboard/Projects/Templates/Connectors so the
 * chrome is defined once (DRY) and every view stays visually consistent.
 */

export type NavId = 'home' | 'projects' | 'templates' | 'connectors' | 'chats'

const NOTIF_META: Record<string, { icon: string; bg: string; fg: string }> = {
  BUILD_FINISHED: { icon: 'M20 6 9 17l-5-5', bg: '#E7F8EF', fg: '#0B6B45' },
  BUILD_FAILED: { icon: 'M18 6 6 18M6 6l12 12', bg: '#FDECEA', fg: '#B42318' },
  PROJECT_SHARED: { icon: 'M4 12v8h16v-8M16 6l-4-4-4 4M12 2v14', bg: '#F1EEFF', fg: '#3D30C9' },
  WORKSPACE_INVITE: { icon: 'M16 21v-2a4 4 0 0 0-8 0v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM19 8v6M22 11h-6', bg: '#FFF1EB', fg: '#A33511' },
  ROLE_CHANGED: { icon: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33', bg: '#E4F1F7', fg: '#135E84' },
}

function Ico({ d, size = 18 }: { d: string; size?: number }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d={d} /></svg>
}

const ICON = {
  pencil: 'M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7M18.375 2.625a2.121 2.121 0 1 1 3 3L12 15l-4 1 1-4Z',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-3.5-3.5',
  chats: 'M7.9 20A9 9 0 1 0 4 16.1L2 22Z',
  projects: 'M5 3h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zm10 0h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM5 13h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2zm10 0h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z',
  connectors: 'M9 2v6M15 2v6M6 8h12v3a6 6 0 0 1-12 0zM12 17v5',
  chevrons: 'm7 15 5 5 5-5M7 9l5-5 5 5',
  invite: 'M16 21v-2a4 4 0 0 0-8 0v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM19 8v6M22 11h-6',
  plus: 'M12 5v14M5 12h14',
  profile: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
  gear: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1',
  billing: 'M3 6h18v12H3zM3 10h18M7 15h3',
  help: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3M12 17h.01',
  logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9',
  bell: 'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.9 1.9 0 0 0 3.4 0',
  arrow: 'M5 12h14M13 6l6 6-6 6',
  doc: 'M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z M3.27 6.96L12 12.01l8.73-5.05 M12 22V12',
  chatIcon: 'M7.9 20A9 9 0 1 0 4 16.1L2 22Z',
}

const NAV: { id: NavId | 'search' | 'chats'; label: string; icon: string; to: string; kbd?: string }[] = [
  { id: 'home', label: 'New', icon: ICON.pencil, to: '/dashboard' },
  { id: 'search', label: 'Search', icon: ICON.search, to: '/search', kbd: '⌘K' },
  { id: 'connectors', label: 'Connectors', icon: ICON.connectors, to: '/connectors' },
  { id: 'chats', label: 'Chats', icon: ICON.chats, to: '/chats' },
  { id: 'projects', label: 'Projects', icon: ICON.projects, to: '/projects' },
]

function initials(text: string) {
  return text.split(/\s|@/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase() || 'K'
}

export default function AppShell({ active, children }: { active?: NavId; children: ReactNode }) {
  const nav = useNavigate()
  const location = useLocation()
  const openProjectId = location.pathname.startsWith('/project/') ? location.pathname.split('/')[2] : null
  const { user, signOut } = useAuth()
  const { workspaces, selected, selectedId, setSelectedId } = useWorkspace()
  // Shares the default projects query with the dashboard grid (same key → one request); slice locally.
  const { data: recentPage } = useProjects(selectedId)
  const recents = (recentPage?.content ?? []).slice(0, 7)
  const { data: unread = 0 } = useUnreadCount()
  const { data: notifsData, isLoading: notifsLoading, refetch: refetchNotifs } = useNotifications()
  const markAllNotifs = useMarkAllNotificationsRead()
  const [pop, setPop] = useState<'none' | 'ws' | 'me' | 'notif'>('none')
  const [notifTab, setNotifTab] = useState<'all' | 'unread'>('all')
  const allNotifs = notifsData ?? []
  const unreadNotifs = allNotifs.filter((n) => !n.read)
  const shownNotifs = notifTab === 'unread' ? unreadNotifs : allNotifs

  const handleOpenNotif = async (n: { id: string; projectId: string | null; read: boolean }) => {
    if (!n.read) {
      try { await notificationApi.markRead(n.id) } catch { /* best-effort */ }
      void refetchNotifs()
    }
    setPop('none')
    if (n.projectId) nav(`/project/${n.projectId}`)
  }
  const [projectMenu, setProjectMenu] = useState<string | null>(null)
  const [deleteConfirmProject, setDeleteConfirmProject] = useState<string | null>(null)
  const delProj = useDeleteProject(selectedId)
  const [chatsOpen, setChatsOpen] = useState(false)
  const [chatsSearch, setChatsSearch] = useState('')
  const [chatsPage, setChatsPage] = useState(0)
  const [chatsFilter, setChatsFilter] = useState<'all' | 'recent' | 'pinned'>('all')
  const [chatsFilterOpen, setChatsFilterOpen] = useState(false)
  const { data: chatsData } = useProjects(selectedId, { types: ['CHAT'] })
  const startProject = useStartProject()
  const CHATS_PAGE_SIZE = 8
  const allChats = useMemo(() => chatsData?.content ?? [], [chatsData])
  const filteredChats = useMemo(() => {
    const q = chatsSearch.trim().toLowerCase()
    if (!q) return allChats
    return allChats.filter(p => p.name.toLowerCase().includes(q) || (p.initialPrompt ?? '').toLowerCase().includes(q))
  }, [allChats, chatsSearch])
  const chatsTotalPages = Math.max(1, Math.ceil(filteredChats.length / CHATS_PAGE_SIZE))
  const chatsSafePage = Math.min(chatsPage, chatsTotalPages - 1)
  const chatsPageItems = filteredChats.slice(chatsSafePage * CHATS_PAGE_SIZE, chatsSafePage * CHATS_PAGE_SIZE + CHATS_PAGE_SIZE)
  const openChats = () => { setChatsSearch(''); setChatsPage(0); setChatsFilter('all'); setChatsFilterOpen(false); setChatsOpen(true) }
  const newChat = async () => {
    const p = await startProject.mutateAsync({ prompt: null, mode: 'CHAT', workspaceId: selectedId ?? undefined })
    setChatsOpen(false)
    nav(`/project/${p.id}`)
  }
  const [width, setWidth] = useState(() => (typeof window !== 'undefined' ? Math.round(window.innerWidth * 0.14) : 220))
  const [isResizing, setIsResizing] = useState(false)
  const [hidePro, setHidePro] = useState(() => localStorage.getItem('kiln_hide_pro') === 'true')
  const collapsed = width < 120

  const startResize = (e: React.MouseEvent) => {
    e.preventDefault()
    setIsResizing(true)
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    const startX = e.clientX
    const startW = width
    const onMove = (me: MouseEvent) => {
      let nw = startW + (me.clientX - startX)
      const maxW = Math.round(window.innerWidth * 0.15) // Max expansion limit set to 14-15% of screen width
      if (nw > maxW) nw = maxW
      if (nw < 120) nw = 42 // Snap to collapsed
      if (nw > 42 && nw < 150) nw = 150 // Snap out of collapsed
      setWidth(nw)
    }
    const onUp = () => {
      setIsResizing(false)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  const wsName = selected?.name ?? 'Your workspace'
  const label = user?.name || user?.email || 'Account'
  const wsColors = ['#16151A', '#5B4CF5', '#9A6000', '#0B7F58', '#C92A66']

  const me = [
    { label: 'Profile', to: '/settings/profile', icon: ICON.profile, danger: false },
    { label: 'Workspace settings', to: '/settings/members', icon: ICON.gear, danger: false },
    { label: 'Billing & usage', to: '/settings/billing-and-usage', icon: ICON.billing, danger: false },
    { label: 'Help & docs', to: '/dashboard', icon: ICON.help, danger: false },
  ]

  return (
    <div className="kap h-screen flex bg-[var(--bg)] text-[var(--ink)] font-['Geist',system-ui,sans-serif] overflow-hidden">
      {pop !== 'none' && <div className="kap-scrim" onClick={() => setPop('none')} />}
      
      {deleteConfirmProject && (
        <div className="kap-dialog-overlay" onClick={() => setDeleteConfirmProject(null)}>
          <div className="kap-dialog" onClick={(e) => e.stopPropagation()}>
            <div className="kap-dialog-title">Delete active project?</div>
            <div className="kap-dialog-body">
              This project is currently open. Are you sure you want to delete it? This action cannot be undone.
            </div>
            <div className="kap-dialog-actions">
              <button type="button" className="kap-dialog-cancel" onClick={() => setDeleteConfirmProject(null)}>
                Cancel
              </button>
              <button type="button" className="kap-dialog-danger" onClick={() => {
                delProj.mutate(deleteConfirmProject, { onSuccess: () => nav('/dashboard') })
                setDeleteConfirmProject(null)
              }}>
                Delete project
              </button>
            </div>
          </div>
        </div>
      )}

      {chatsOpen && (
        <div className="kap-dialog-overlay" onClick={() => { setChatsOpen(false); setChatsFilterOpen(false) }}>
          <div className="kap-chats-modal" onClick={(e) => e.stopPropagation()}>

            {/* Header */}
            <div className="kap-chats-modal-hd">
              <button type="button" className="kap-chats-modal-ico-btn" onClick={() => setChatsOpen(false)} aria-label="Back">
                <Ico d="m15 18-6-6 6-6" size={17} />
              </button>
              <span className="kap-chats-modal-title">Chats</span>
              <div style={{ flexGrow: 1 }} />
              <button type="button" className="kap-chats-newbtn" disabled={startProject.isPending} onClick={() => void newChat()}>
                {startProject.isPending ? <Spinner size={13} /> : null} New chat
              </button>
              <button type="button" className="kap-chats-modal-ico-btn" onClick={() => setChatsOpen(false)} aria-label="Close">
                <Ico d="M18 6L6 18M6 6l12 12" size={16} />
              </button>
            </div>

            {/* Search + filter row */}
            <div className="kap-chats-modal-bar">
              <div className="kap-chats-search">
                <Ico d="M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z" size={14} />
                <input
                  type="search"
                  placeholder="Search chats..."
                  value={chatsSearch}
                  onChange={(e) => { setChatsSearch(e.target.value); setChatsPage(0) }}
                  aria-label="Search chats"
                  autoFocus
                />
                {chatsSearch && (
                  <button type="button" className="kap-chats-clear" aria-label="Clear" onClick={() => { setChatsSearch(''); setChatsPage(0) }}>
                    <Ico d="M18 6L6 18M6 6l12 12" size={11} />
                  </button>
                )}
              </div>
              <div style={{ position: 'relative' }}>
                <button type="button" className="kap-chats-filter-btn" onClick={() => setChatsFilterOpen((v) => !v)}>
                  {{ all: 'All', recent: 'Recent', pinned: 'Pinned' }[chatsFilter]}
                  <Ico d="m6 9 6 6 6-6" size={12} />
                </button>
                {chatsFilterOpen && (
                  <>
                    <div className="kap-scrim" style={{ zIndex: 210 }} onClick={() => setChatsFilterOpen(false)} />
                    <div className="kap-pop" style={{ right: 0, top: 38, width: 130, zIndex: 211 }}>
                      {(['all', 'recent', 'pinned'] as const).map((f) => (
                        <button key={f} type="button" className="kap-pop-item" onClick={() => { setChatsFilter(f); setChatsFilterOpen(false); setChatsPage(0) }}>
                          {{ all: 'All', recent: 'Recent', pinned: 'Pinned' }[f]}
                        </button>
                      ))}
                    </div>
                  </>
                )}
              </div>
            </div>

            {/* List */}
            <div className="kap-chats-modal-body">
              {filteredChats.length === 0 ? (
                <div className="kap-chats-empty">
                  {chatsSearch ? `No chats match "${chatsSearch}"` : 'No chats yet — start a new one.'}
                </div>
              ) : (
                chatsPageItems.map((p, i) => (
                  <button key={p.id} type="button" className={`kap-cm-row ${i < chatsPageItems.length - 1 ? 'sep' : ''}`} onClick={() => { setChatsOpen(false); nav(`/project/${p.id}`) }}>
                    <span className="kap-cm-info">
                      <span className="kap-cm-name">
                        {p.name}
                        {p.deploymentStatus === 'LIVE' && <i className="kap-cm-dot" />}
                      </span>
                      <span className="kap-cm-time">{relTime(p.lastActivityAt)}</span>
                    </span>
                  </button>
                ))
              )}
            </div>

            {/* Pagination */}
            {chatsTotalPages > 1 && (
              <div className="kap-chats-pager">
                <button type="button" className="kap-pager-btn" disabled={chatsSafePage === 0} onClick={() => setChatsPage((p) => p - 1)} aria-label="Previous">
                  <Ico d="m15 18-6-6 6-6" size={15} />
                </button>
                <span className="kap-pager-info">{chatsSafePage + 1} / {chatsTotalPages}</span>
                <button type="button" className="kap-pager-btn" disabled={chatsSafePage >= chatsTotalPages - 1} onClick={() => setChatsPage((p) => p + 1)} aria-label="Next">
                  <Ico d="m9 18 6-6-6-6" size={15} />
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      <aside className={`kap-side ${collapsed ? 'collapsed' : ''}`} style={{ width }}>
        <div className={`kap-resizer ${isResizing ? 'active' : ''}`} onMouseDown={startResize} />

        <Link to="/" className="kap-brand"><span className="kap-brand-orb"><i /></span><b>Kiln</b></Link>

        <div className="kap-ws-wrap">
          <button
            type="button"
            className={`kap-ws ${pop === 'ws' ? 'open' : ''}`}
            aria-expanded={pop === 'ws'}
            onClick={() => setPop(pop === 'ws' ? 'none' : 'ws')}
          >
            <span className="kap-ws-badge">{initials(label)[0]}</span>
            <span className="kap-ws-text" style={{ display: 'flex', flexDirection: 'column', flexGrow: 1, minWidth: 0 }}>
              <span className="kap-ws-name">{wsName}</span>
            </span>
            <span className={`kap-ws-chevron ${pop === 'ws' ? 'open' : ''}`}>
              <Ico d="m6 9 6 6 6-6" size={15} />
            </span>
          </button>

          {pop === 'ws' && (
            <div className="kap-ws-pop">
              {/* Workspace Header Card */}
              <div className="kap-ws-pop-header">
                <div className="kap-ws-pop-avatar">
                  <Ico d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2M12 3v12M8 7l4-4 4 4" size={19} />
                </div>
                <div className="kap-ws-pop-info">
                  <div className="kap-ws-pop-title">{wsName}</div>
                  <div className="kap-ws-pop-subtitle">Free Plan • 1 member</div>
                </div>
              </div>

              {/* Quick Action Buttons */}
              <div className="kap-ws-pop-actions">
                <Link to="/settings/members" className="kap-ws-btn" onClick={() => setPop('none')}>
                  <Ico d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8M19 8v6M22 11h-6" size={15} />
                  <span>Invite members</span>
                </Link>
                <Link to="/settings/members" className="kap-ws-btn" onClick={() => setPop('none')}>
                  <Ico d={ICON.gear} size={15} />
                  <span>Workspace settings</span>
                </Link>
              </div>

              <div className="kap-ws-divider" />

              {/* Credits Section */}
              <div className="kap-ws-credits">
                <div className="kap-ws-credits-head">
                  <span className="kap-ws-credits-title">Credits</span>
                  <Link to="/settings/billing-and-usage" className="kap-ws-credits-badge" onClick={() => setPop('none')}>
                    5 left <Ico d="M9 18l6-6-6-6" size={12} />
                  </Link>
                </div>
                <div className="kap-ws-progress">
                  <div className="kap-ws-progress-fill" style={{ width: '100%' }} />
                </div>
                <div className="kap-ws-credits-reset">
                  <span className="kap-ws-bullet" />
                  <span>Daily credits reset at midnight UTC</span>
                </div>
              </div>

              {/* Multi-workspace list if any */}
              {workspaces.length > 1 && (
                <>
                  <div className="kap-ws-divider" />
                  <div className="kap-ws-list">
                    {workspaces.map((w, i) => (
                      <button
                        key={w.id}
                        type="button"
                        className={`kap-ws-item ${w.id === selectedId ? 'active' : ''}`}
                        onClick={() => { setSelectedId(w.id); setPop('none') }}
                      >
                        <span className="kap-ws-item-badge" style={{ background: wsColors[i % wsColors.length] }}>
                          {initials(w.name)[0]}
                        </span>
                        <span className="kap-ws-item-name">{w.name}</span>
                        {w.id === selectedId && <Ico d="M20 6 9 17l-5-5" size={15} />}
                      </button>
                    ))}
                  </div>
                </>
              )}

              <div className="kap-ws-divider" />

              {/* New workspace option */}
              <Link to="/onboarding/workspace" className="kap-ws-new-link" onClick={() => setPop('none')}>
                <Ico d={ICON.plus} size={16} />
                <span>New workspace</span>
              </Link>

              <div className="kap-ws-divider" />

              {/* Turn Pro footer */}
              <div className="kap-ws-footer">
                <div className="kap-ws-pro-tag">
                  <Ico d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" size={15} />
                  <span>Turn Pro</span>
                </div>
                <Link to="/settings/billing-and-usage" className="kap-ws-upgrade-btn" onClick={() => setPop('none')}>
                  Upgrade
                </Link>
              </div>
            </div>
          )}
        </div>

        <nav className="kap-nav">
          {NAV.map((n) => (
            <button
              key={n.id}
              type="button"
              className={!openProjectId && (active === n.id || (n.id === 'chats' && chatsOpen)) ? 'on' : ''}
              onClick={() => n.id === 'chats' ? openChats() : nav(n.to)}
            >
              <Ico d={n.icon} size={18} /><span style={{ flexGrow: 1 }}>{n.label}</span>{n.kbd && <span className="kbd">{n.kbd}</span>}
            </button>
          ))}
        </nav>

        {!collapsed && (
          <div className="kap-recents">
            <span className="kap-recents-h">Recents</span>
            {recents.map((p) => (
              <div key={p.id} className="kap-recent-item" onMouseLeave={() => setProjectMenu(null)}>
                <Link to={`/project/${p.id}`} className={openProjectId === p.id ? 'on' : ''}>
                  <Ico d={p.type === 'CHAT' ? ICON.chats : ICON.projects} size={18} />
                  <span>{p.name}</span>
                </Link>
                <button
                  type="button"
                  className={`kap-recent-dots ${projectMenu === p.id ? 'active' : ''}`}
                  aria-label="Project actions"
                  onClick={(e) => { e.preventDefault(); setProjectMenu(projectMenu === p.id ? null : p.id) }}
                >
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor">
                    <circle cx="5" cy="12" r="1.4" />
                    <circle cx="12" cy="12" r="1.4" />
                    <circle cx="19" cy="12" r="1.4" />
                  </svg>
                </button>
                {projectMenu === p.id && (
                  <div className="kap-pop kap-recent-pop">
                    <button type="button" onClick={(e) => { 
                      e.preventDefault()
                      if (openProjectId === p.id) {
                        setDeleteConfirmProject(p.id)
                      } else {
                        delProj.mutate(p.id)
                      }
                      setProjectMenu(null)
                    }} style={{ color: '#C63B2B' }}>
                      <Ico d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" size={15} /> Delete
                    </button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}

        {!hidePro && (
          <div className="kap-pro">
            <span className="kap-pro-orb" />
            <button type="button" className="kap-pro-close" onClick={() => { setHidePro(true); localStorage.setItem('kiln_hide_pro', 'true') }} aria-label="Dismiss">
              <Ico d="M18 6L6 18M6 6l12 12" size={15} />
            </button>
            <div style={{ position: 'relative', paddingRight: '12px' }}><b>Go Pro</b><p>More builds, custom domains and private projects.</p></div>
            <Link to="/pricing">See plans</Link>
          </div>
        )}

        <button type="button" className="kap-me" aria-expanded={pop === 'me'} onClick={() => setPop(pop === 'me' ? 'none' : 'me')}>
          <span className="kap-me-badge">{initials(label)}</span>
          <span className="kap-me-name">{user?.name || 'Kiln builder'}</span>
        </button>
        {pop === 'me' && (
          <div className="kap-pop" style={{ left: collapsed ? 42 : 14, bottom: collapsed ? 8 : 56, width: 244 }}>
            {user?.email && (
              <>
                <div className="kap-pop-user">
                  <span className="kap-pop-user-mail" title={user.email}>{user.email}</span>
                </div>
                <div className="kap-pop-sep" />
              </>
            )}
            {me.map((m) => (
              <Link key={m.label} to={m.to} className="kap-pop-item" onClick={() => setPop('none')}><Ico d={m.icon} size={16} />{m.label}</Link>
            ))}
            <div className="kap-pop-sep" />
            <button type="button" className="kap-pop-item" style={{ color: '#B42318' }} onClick={() => { signOut(); nav('/') }}><Ico d={ICON.logout} size={16} />Log out</button>
          </div>
        )}
      </aside>

      <main className="kap-main">
        {!openProjectId && (
          <div className="kap-top">
            {active === 'home' ? (
              <span className="kap-whatsnew"><span>New</span>Multiplayer — build together in one project<Ico d={ICON.arrow} size={13} /></span>
            ) : (
              <button type="button" className="kap-search-btn" onClick={() => nav('/search')}>
                <Ico d={ICON.search} size={15} /><span>Search projects, chats and actions</span><kbd>⌘K</kbd>
              </button>
            )}
            <div style={{ flexGrow: 1 }} />
            <div className="kap-notif-wrap" style={{ position: 'relative' }}>
              <button
                type="button"
                className={`kap-bell ${pop === 'notif' ? 'open' : ''}`}
                aria-label={`Notifications${unread > 0 ? ` (${unread} unread)` : ''}`}
                aria-expanded={pop === 'notif'}
                onClick={() => setPop(pop === 'notif' ? 'none' : 'notif')}
              >
                <Ico d={ICON.bell} size={15} />
                {unread > 0 && <span className="dot">{unread > 9 ? '9+' : unread}</span>}
              </button>

              {pop === 'notif' && (
                <div className="kap-notif-pop">
                  <div className="kap-notif-pop-head">
                    <div className="kap-notif-pop-title-wrap">
                      <span className="kap-notif-pop-title">Notifications</span>
                      {unread > 0 && <span className="kap-notif-pop-count">{unread} unread</span>}
                    </div>
                    {unread > 0 && (
                      <button
                        type="button"
                        className="kap-notif-pop-mark"
                        onClick={() => markAllNotifs.mutate(undefined, { onSuccess: () => void refetchNotifs() })}
                        disabled={markAllNotifs.isPending}
                      >
                        <Ico d="M20 6 9 17l-5-5" size={13} />
                        <span>Mark all read</span>
                      </button>
                    )}
                  </div>

                  <div className="kap-notif-pop-tabs">
                    <button
                      type="button"
                      className={`kap-notif-pop-tab ${notifTab === 'all' ? 'on' : ''}`}
                      onClick={() => setNotifTab('all')}
                    >
                      All <span className="t-count">{allNotifs.length}</span>
                    </button>
                    <button
                      type="button"
                      className={`kap-notif-pop-tab ${notifTab === 'unread' ? 'on' : ''}`}
                      onClick={() => setNotifTab('unread')}
                    >
                      Unread <span className="t-count">{unreadNotifs.length}</span>
                    </button>
                  </div>

                  <div className="kap-notif-pop-list">
                    {notifsLoading ? (
                      <div style={{ display: 'grid', placeItems: 'center', padding: '36px', color: 'var(--muted)', fontSize: '13px' }}>
                        Loading notifications...
                      </div>
                    ) : shownNotifs.length === 0 ? (
                      <div className="kap-notif-pop-empty">
                        <div className="kap-notif-pop-empty-icon">
                          <Ico d={ICON.bell} size={20} />
                        </div>
                        <div className="kap-notif-pop-empty-title">
                          {notifTab === 'unread' ? "You're all caught up!" : 'No notifications yet'}
                        </div>
                        <div className="kap-notif-pop-empty-hint">
                          {notifTab === 'unread'
                            ? 'No unread updates in your workspace.'
                            : 'Activity on your projects and workspace will show up here.'}
                        </div>
                      </div>
                    ) : (
                      shownNotifs.map((n) => {
                        const m = NOTIF_META[n.type] ?? { icon: 'M12 2v14', bg: '#F1EEFF', fg: '#3D30C9' }
                        return (
                          <button
                            key={n.id}
                            type="button"
                            className={`kap-notif-pop-item ${n.read ? '' : 'unread'}`}
                            onClick={() => void handleOpenNotif(n)}
                          >
                            <span className="kap-notif-pop-icon" style={{ background: m.bg, color: m.fg }}>
                              <Ico d={m.icon} size={15} />
                            </span>
                            <span className="kap-notif-pop-content">
                              <span className="kap-notif-pop-row">
                                <b className="kap-notif-pop-name">{n.title}</b>
                                <small className="kap-notif-pop-time">{relTime(n.createdAt)}</small>
                              </span>
                              {n.body && <span className="kap-notif-pop-body">{n.body}</span>}
                            </span>
                            {!n.read && <span className="kap-notif-pop-dot" />}
                          </button>
                        )
                      })
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>
        )}
        {children}
      </main>
    </div>
  )
}
