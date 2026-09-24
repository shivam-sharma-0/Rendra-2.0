import { useCallback, useEffect, useState } from 'react'
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { CHAT_TYPES, PROJECT_TYPES, projectApi, startProject, workspaceApi, type Project, type ProjectType } from '../api/projects'
import { useAuth } from '../auth/AuthContext'
import PromptBar from '../components/PromptBar'
import Sidebar from '../components/Sidebar'

const TABS = ['My projects', 'Recently viewed', 'Templates'] as const
type Tab = (typeof TABS)[number]

/** The sidebar's Chats/Projects entries drive this via ?view=. */
function typesForView(view: string | null): ProjectType[] | undefined {
  if (view === 'chats') return CHAT_TYPES
  if (view === 'projects') return PROJECT_TYPES
  return undefined
}

export default function Dashboard() {
  const { user, isAuthenticated, loading } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [tab, setTab] = useState<Tab>('My projects')

  const view = params.get('view')
  const types = typesForView(view)
  const typesKey = types?.join(',') ?? 'all' // stable dep for the fetch effect

  const [workspaceId, setWorkspaceId] = useState<string | null>(null)
  const [projects, setProjects] = useState<Project[] | null>(null)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Resolve the personal workspace once.
  useEffect(() => {
    if (!isAuthenticated) return
    let cancelled = false
    ;(async () => {
      try {
        const workspaces = await workspaceApi.list()
        const ws = workspaces.find((w) => w.personal) ?? workspaces[0]
        if (!cancelled && ws) setWorkspaceId(ws.id)
      } catch {
        if (!cancelled) setProjects([])
      }
    })()
    return () => {
      cancelled = true
    }
  }, [isAuthenticated])

  // (Re)load projects whenever the workspace or the type filter changes.
  useEffect(() => {
    if (!workspaceId) return
    let cancelled = false
    setProjects(null)
    ;(async () => {
      try {
        const page = await projectApi.list(workspaceId, { types })
        if (!cancelled) setProjects(page.content)
      } catch {
        if (!cancelled) setProjects([])
      }
    })()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId, typesKey])

  const startBuild = useCallback(
    async (prompt: string, mode: 'BUILD' | 'CHAT' | 'PLAN') => {
      if (creating) return
      setCreating(true)
      setError(null)
      try {
        const project = await startProject(prompt, mode)
        navigate(`/project/${project.id}`)
      } catch {
        setError('Could not start the project. Please try again.')
        setCreating(false)
      }
    },
    [creating, navigate],
  )

  // Guard: the dashboard is for signed-in users only.
  if (loading) return null
  if (!isAuthenticated) return <Navigate to="/" replace />

  const initial = (user?.name?.[0] ?? user?.email?.[0] ?? 'U').toUpperCase()

  return (
    <div className="flex min-h-screen">
      <Sidebar />

      {/* Scrollable content column; the sidebar stays fixed. */}
      <main className="relative h-screen flex-1 overflow-y-auto">
        {/* Hero — greeting + chat bar, fills most of the first screen. */}
        <section className="relative flex min-h-[86vh] flex-col justify-center overflow-hidden px-8">
          <div
            aria-hidden
            className="pointer-events-none absolute inset-0 -z-0"
            style={{
              background:
                'radial-gradient(90% 70% at 50% 120%, var(--glow-1), transparent 60%), radial-gradient(70% 60% at 85% 110%, var(--glow-2), transparent 60%)',
            }}
          />
          <div className="relative">
            <h1 className="font-display text-center text-3xl font-bold text-text sm:text-[2.5rem]">
              What are we building today, <span className="text-accent-gradient">{firstName(user)}</span>?
            </h1>
            <div className="mx-auto mt-7 w-full max-w-2xl">
              <PromptBar
                onSubmit={startBuild}
                placeholder={creating ? 'Starting your project…' : 'Ask Rendra to build something…'}
              />
              {error && <p className="mt-2 text-center text-[13px] font-medium text-danger">{error}</p>}
            </div>
          </div>
        </section>

        {/* Projects — tab bar + grid, revealed on scroll. */}
        <section className="relative px-8 pb-20">
          {/* Tab bar */}
          <div className="flex items-center justify-between gap-3 rounded-2xl border border-border bg-[color:var(--surface)] px-3 py-2.5 shadow-[var(--shadow-sm)]">
            <div className="flex items-center gap-1.5">
              <button className="inline-flex items-center gap-2 rounded-full border border-border px-3.5 py-1.5 text-[13px] text-muted transition hover:text-text">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
                  <circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" />
                </svg>
                Search
              </button>
              {TABS.map((t) => (
                <button
                  key={t}
                  onClick={() => setTab(t)}
                  className={
                    'rounded-full px-3.5 py-1.5 text-[13px] font-medium transition ' +
                    (tab === t ? 'bg-[color:var(--accent-subtle)] text-accent' : 'text-muted hover:text-text')
                  }
                >
                  {t}
                </button>
              ))}
            </div>
            <button className="inline-flex items-center gap-1.5 pr-1 text-[13px] font-semibold text-muted transition hover:text-text">
              Browse all
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                <path d="M5 12h14M13 6l6 6-6 6" />
              </svg>
            </button>
          </div>

          {/* Grid */}
          {tab === 'Templates' ? (
            <p className="py-16 text-center text-[13px] text-faint">Starter templates coming soon.</p>
          ) : projects === null ? (
            <div className="mt-6 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
              {Array.from({ length: 6 }).map((_, i) => (
                <div key={i} className="animate-pulse">
                  <div className="aspect-[16/10] rounded-xl border border-border bg-[color:var(--surface)]" />
                  <div className="mt-2.5 h-4 w-2/3 rounded bg-[color:var(--surface)]" />
                </div>
              ))}
            </div>
          ) : projects.length === 0 ? (
            <p className="py-16 text-center text-[13px] text-faint">
              {view === 'chats'
                ? 'No chats yet — start one above in Chat mode.'
                : view === 'projects'
                  ? 'No projects yet — describe an idea above to build one.'
                  : 'Nothing here yet — describe an idea above to create your first one.'}
            </p>
          ) : (
            <div className="mt-6 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
              {projects.map((p) => (
                <article
                  key={p.id}
                  onClick={() => navigate(`/project/${p.id}`)}
                  className="group cursor-pointer"
                >
                  <div className="grid aspect-[16/10] place-items-center overflow-hidden rounded-xl border border-border bg-[color:var(--surface)] transition group-hover:border-[color:var(--border-strong)] group-hover:shadow-[var(--shadow-pop)]">
                    <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="var(--faint)" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                      <path d="M9 8.5 5.5 12 9 15.5M15 8.5 18.5 12 15 15.5" />
                    </svg>
                  </div>
                  <div className="mt-2.5 flex items-center gap-2.5 px-0.5">
                    <span
                      className="grid h-8 w-8 shrink-0 place-items-center rounded-full text-[12px] font-bold text-white"
                      style={{ background: 'linear-gradient(140deg, var(--accent), var(--accent-active))' }}
                    >
                      {initial}
                    </span>
                    <div className="min-w-0">
                      <div className="truncate text-sm font-semibold text-text">{p.name}</div>
                      <div className="text-[11px] text-faint">Edited {formatDate(p.lastActivityAt)}</div>
                    </div>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </main>
    </div>
  )
}

function firstName(user: { name?: string | null; email?: string } | null) {
  if (!user) return 'builder'
  return user.name?.split(' ')[0] ?? user.email?.split('@')[0] ?? 'builder'
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}
