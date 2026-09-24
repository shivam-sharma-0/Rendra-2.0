import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { PROJECT_TYPES, type Project } from '../api/projects'
import { useAuth } from '../auth/AuthContext'
import AppShell from '../components/AppShell'
import { useProjects } from '../hooks/useProjects'
import { timeAgo } from '../lib/time'

export default function ProjectsPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const projects = useProjects(PROJECT_TYPES)
  const [query, setQuery] = useState('')

  const initial = (user?.name?.[0] ?? user?.email?.[0] ?? 'U').toUpperCase()
  const filtered = useMemo(
    () => (projects ?? []).filter((p) => p.name.toLowerCase().includes(query.trim().toLowerCase())),
    [projects, query],
  )

  return (
    <AppShell>
      <div className="mx-auto max-w-6xl px-8 py-10">
        {/* Header */}
        <div className="flex items-center justify-between">
          <h1 className="font-display text-2xl font-bold text-text">Projects</h1>
          <div className="flex items-center gap-2">
            <button className="rounded-[10px] border border-[color:var(--border-strong)] px-3.5 py-2 text-[13px] font-semibold text-text transition hover:bg-black/[0.03] dark:hover:bg-white/[0.05]">
              New folder
            </button>
            <button
              onClick={() => navigate('/dashboard')}
              className="rounded-[10px] bg-[color:var(--spark)] px-3.5 py-2 text-[13px] font-semibold text-[color:var(--spark-contrast)] shadow-[var(--shadow-pop)] transition hover:bg-[color:var(--spark-hover)]"
            >
              New project
            </button>
          </div>
        </div>

        {/* Toolbar */}
        <div className="mt-6 flex items-center gap-2.5">
          <SearchInput value={query} onChange={setQuery} placeholder="Search projects…" />
          <ToolbarButton label="Filter" />
          <ToolbarButton label="View" />
        </div>

        {/* Grid */}
        {projects === null ? (
          <SkeletonGrid />
        ) : filtered.length === 0 ? (
          <p className="py-20 text-center text-[13px] text-faint">
            {query ? 'No projects match your search.' : 'No projects yet — start one from the home prompt.'}
          </p>
        ) : (
          <div className="mt-6 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {filtered.map((p) => (
              <ProjectCard key={p.id} project={p} initial={initial} onOpen={() => navigate(`/project/${p.id}`)} />
            ))}
          </div>
        )}
      </div>
    </AppShell>
  )
}

function ProjectCard({ project, initial, onOpen }: { project: Project; initial: string; onOpen: () => void }) {
  return (
    <article onClick={onOpen} className="group cursor-pointer">
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
          <div className="truncate text-sm font-semibold text-text">{project.name}</div>
          <div className="text-[11px] text-faint">Edited {timeAgo(project.lastActivityAt)}</div>
        </div>
      </div>
    </article>
  )
}

function SkeletonGrid() {
  return (
    <div className="mt-6 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} className="animate-pulse">
          <div className="aspect-[16/10] rounded-xl border border-border bg-[color:var(--surface)]" />
          <div className="mt-2.5 h-4 w-2/3 rounded bg-[color:var(--surface)]" />
        </div>
      ))}
    </div>
  )
}

function SearchInput({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <div className="flex flex-1 items-center gap-2 rounded-[10px] border border-border bg-[color:var(--surface)] px-3 py-2">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="var(--muted)" strokeWidth="2" strokeLinecap="round" aria-hidden>
        <circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" />
      </svg>
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full bg-transparent text-[13px] text-text outline-none placeholder:text-muted/70"
      />
    </div>
  )
}

function ToolbarButton({ label }: { label: string }) {
  return (
    <button className="inline-flex items-center gap-1.5 rounded-[10px] border border-border px-3 py-2 text-[13px] font-medium text-text transition hover:bg-black/[0.03] dark:hover:bg-white/[0.05]">
      {label}
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="m6 9 6 6 6-6" />
      </svg>
    </button>
  )
}
