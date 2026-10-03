import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { useProjects } from '../../api/queries'
import type { Project } from '../../api/projects'
import { EmptyState, ErrorState, Spinner } from '../../components/ui/States'
import AppShell from '../AppShell'
import { Ico, relTime, statusOf, paletteFor } from '../parts'

/**
 * Projects — Figma "08" Projects view. Real workspace projects (React Query) with tabs, status
 * filter, sort and list/grid layout (persisted). Starring is client-side (localStorage) since the
 * backend has no starred flag yet; "Shared with me" is empty until project-level sharing exists (P8),
 * so it shows an honest empty state rather than faking ownership data.
 */

const STAR_KEY = 'rendra_starred'
const LAYOUT_KEY = 'rendra_projects_layout'

type Tab = 'all' | 'mine' | 'shared' | 'starred'
type StatusFilter = 'any' | 'LIVE' | 'PENDING' | 'NONE'

const STATUS_OPTS: { id: StatusFilter; label: string }[] = [
  { id: 'any', label: 'Any status' }, { id: 'LIVE', label: 'Live' },
  { id: 'PENDING', label: 'Building' }, { id: 'NONE', label: 'Draft' },
]

function loadStarred(): Set<string> {
  try { return new Set(JSON.parse(localStorage.getItem(STAR_KEY) || '[]') as string[]) } catch { return new Set() }
}

export default function Projects() {
  const nav = useNavigate()
  const { selectedId } = useWorkspace()
  const { data: page, isLoading, isError, refetch } = useProjects(selectedId)
  const all = useMemo(() => page?.content ?? [], [page])

  const [tab, setTab] = useState<Tab>('all')
  const [status, setStatus] = useState<StatusFilter>('any')
  const [statusOpen, setStatusOpen] = useState(false)
  const [sortByName, setSortByName] = useState(false)
  const [layout, setLayout] = useState<'list' | 'grid'>(() => (localStorage.getItem(LAYOUT_KEY) as 'list' | 'grid') || 'list')
  const [starred, setStarred] = useState<Set<string>>(loadStarred)

  const setLayoutPersist = (l: 'list' | 'grid') => { setLayout(l); localStorage.setItem(LAYOUT_KEY, l) }
  const toggleStar = (id: string) => {
    setStarred((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      localStorage.setItem(STAR_KEY, JSON.stringify([...next]))
      return next
    })
  }

  const filtered = useMemo(() => {
    let list: Project[] = tab === 'shared' ? [] : tab === 'starred' ? all.filter((p) => starred.has(p.id)) : all
    if (status !== 'any') list = list.filter((p) => p.deploymentStatus === status)
    if (sortByName) list = [...list].sort((a, b) => a.name.localeCompare(b.name))
    return list
  }, [all, tab, status, sortByName, starred])

  const tabs: { id: Tab; label: string; n: number }[] = [
    { id: 'all', label: 'All', n: all.length },
    { id: 'mine', label: 'Created by me', n: all.length },
    { id: 'shared', label: 'Shared with me', n: 0 },
    { id: 'starred', label: 'Starred', n: all.filter((p) => starred.has(p.id)).length },
  ]

  return (
    <AppShell active="projects">
      <div className="kap-page">
        <div className="kap-ph">
          <div>
            <h1>Projects</h1>
            <p>Everything your workspace is building — yours and your team's.</p>
          </div>
          <button type="button" className="kap-newbtn" onClick={() => nav('/dashboard')}>
            <Ico d="M12 5v14M5 12h14" size={15} sw={2.4} /> New project
          </button>
        </div>

        <div className="kap-toolbar">
          <div className="kap-tabs">
            {tabs.map((t) => (
              <button key={t.id} type="button" className={`kap-tab ${tab === t.id ? 'on' : ''}`} onClick={() => setTab(t.id)}>
                {t.label} <span className="n">{t.n}</span>
              </button>
            ))}
          </div>
          <div style={{ flexGrow: 1 }} />
          <div style={{ position: 'relative' }}>
            <button type="button" className="kap-fbtn" aria-expanded={statusOpen} onClick={() => setStatusOpen((v) => !v)}>
              <Ico d="M4 6h16M7 12h10M10 18h4" size={14} sw={2} /> {STATUS_OPTS.find((s) => s.id === status)!.label}
            </button>
            {statusOpen && (
              <>
                <div className="kap-scrim" onClick={() => setStatusOpen(false)} />
                <div className="kap-pop" style={{ right: 0, top: 44, width: 180 }}>
                  {STATUS_OPTS.map((s) => (
                    <button key={s.id} type="button" className="kap-pop-item" onClick={() => { setStatus(s.id); setStatusOpen(false) }}>{s.label}</button>
                  ))}
                </div>
              </>
            )}
          </div>
          <button type="button" className="kap-fbtn" onClick={() => setSortByName((v) => !v)}>
            {sortByName ? 'Name' : 'Last edited'} <Ico d="m6 9 6 6 6-6" size={12} sw={2.4} />
          </button>
          <div className="kap-seg">
            <button type="button" aria-label="List view" className={layout === 'list' ? 'on' : ''} onClick={() => setLayoutPersist('list')}>
              <Ico d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01" size={15} sw={2} />
            </button>
            <button type="button" aria-label="Grid view" className={layout === 'grid' ? 'on' : ''} onClick={() => setLayoutPersist('grid')}>
              <Ico d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z" size={15} sw={2} />
            </button>
          </div>
        </div>

        {isLoading ? (
          <div style={{ display: 'grid', placeItems: 'center', padding: 60 }}><Spinner /></div>
        ) : isError ? (
          <ErrorState message="We couldn't load projects." onRetry={() => void refetch()} />
        ) : filtered.length === 0 ? (
          <EmptyState
            title={tab === 'shared' ? 'Nothing shared with you yet' : tab === 'starred' ? 'No starred projects' : 'No projects yet'}
            hint={tab === 'starred' ? 'Star a project to pin it here.' : tab === 'shared' ? 'Projects teammates share will appear here.' : 'Start a new project from the dashboard.'}
          />
        ) : layout === 'list' ? (
          <div className="kap-list">
            <div className="kap-list-head"><span>Name</span><span>Status</span><span>Last edited</span><span /></div>
            {filtered.map((p) => {
              const c = paletteFor(p.id); const st = statusOf(p)
              return (
                <button key={p.id} type="button" className="kap-row" onClick={() => nav(`/project/${p.id}`)}>
                  <span className="kap-row-name">
                    <span className="kap-row-thumb" style={{ background: c.bg }} />
                    <span style={{ minWidth: 0 }}><b>{p.name}</b><small>{p.type === 'CHAT' ? 'Conversation' : p.type === 'PLAN' ? 'Plan' : 'App'}</small></span>
                  </span>
                  <span><span className="kap-status" style={{ background: st.bg, color: st.ink }}><i style={{ background: st.dot }} />{st.label}</span></span>
                  <span className="kap-row-time">{relTime(p.lastActivityAt)}</span>
                  <span
                    className={`kap-star ${starred.has(p.id) ? 'on' : ''}`}
                    role="button"
                    tabIndex={0}
                    aria-label={starred.has(p.id) ? 'Unstar' : 'Star'}
                    onClick={(e) => { e.stopPropagation(); toggleStar(p.id) }}
                    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.stopPropagation(); toggleStar(p.id) } }}
                  >
                    <Ico d="M12 2l3 6.5 7 .9-5 4.7 1.3 6.9L12 17.8 5.4 21l1.3-6.9-5-4.7 7-.9z" size={16} sw={starred.has(p.id) ? 0 : 2} />
                  </span>
                </button>
              )
            })}
          </div>
        ) : (
          <div className="kap-grid4">
            {filtered.map((p) => {
              const c = paletteFor(p.id)
              return (
                <button key={p.id} type="button" className="kap-gcard" onClick={() => nav(`/project/${p.id}`)}>
                  <span className="kap-gthumb" style={{ background: c.bg }}>
                    <span className="l1" style={{ background: c.fg }} />
                    <span className="l2" style={{ background: c.fg }} />
                    <span className="row"><span style={{ background: c.card }} /><span style={{ background: c.card }} /><span style={{ background: c.card }} /></span>
                  </span>
                  <span className="kap-gmeta">
                    <span style={{ flexGrow: 1, minWidth: 0 }}><b>{p.name}</b><small>Edited {relTime(p.lastActivityAt)}</small></span>
                  </span>
                </button>
              )
            })}
          </div>
        )}
      </div>
    </AppShell>
  )
}
