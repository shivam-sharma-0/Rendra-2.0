import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { CHAT_TYPES } from '../api/projects'
import AppShell from '../components/AppShell'
import { useProjects } from '../hooks/useProjects'
import { timeAgo } from '../lib/time'

export default function ChatsPage() {
  const navigate = useNavigate()
  const chats = useProjects(CHAT_TYPES)
  const [query, setQuery] = useState('')

  const filtered = useMemo(
    () => (chats ?? []).filter((c) => c.name.toLowerCase().includes(query.trim().toLowerCase())),
    [chats, query],
  )

  return (
    <AppShell>
      <div className="mx-auto max-w-4xl px-8 py-10">
        {/* Header */}
        <div className="flex items-center justify-between">
          <h1 className="font-display text-2xl font-bold text-text">Chats</h1>
          <button
            onClick={() => navigate('/dashboard')}
            className="rounded-[10px] bg-[color:var(--spark)] px-3.5 py-2 text-[13px] font-semibold text-[color:var(--spark-contrast)] shadow-[var(--shadow-pop)] transition hover:bg-[color:var(--spark-hover)]"
          >
            New chat
          </button>
        </div>

        {/* Toolbar */}
        <div className="mt-6 flex items-center gap-2.5">
          <div className="flex flex-1 items-center gap-2 rounded-[10px] border border-border bg-[color:var(--surface)] px-3 py-2">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="var(--muted)" strokeWidth="2" strokeLinecap="round" aria-hidden>
              <circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" />
            </svg>
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search chats…"
              className="w-full bg-transparent text-[13px] text-text outline-none placeholder:text-muted/70"
            />
          </div>
          <button className="inline-flex items-center gap-1.5 rounded-[10px] border border-border px-3 py-2 text-[13px] font-medium text-text transition hover:bg-black/[0.03] dark:hover:bg-white/[0.05]">
            All
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="m6 9 6 6 6-6" />
            </svg>
          </button>
        </div>

        {/* List */}
        {chats === null ? (
          <div className="mt-4 space-y-2">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="animate-pulse border-b border-border py-4">
                <div className="h-4 w-1/3 rounded bg-[color:var(--surface)]" />
                <div className="mt-2 h-3 w-20 rounded bg-[color:var(--surface)]" />
              </div>
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <p className="py-20 text-center text-[13px] text-faint">
            {query ? 'No chats match your search.' : 'No chats yet — start one from the home prompt in Chat mode.'}
          </p>
        ) : (
          <div className="mt-4">
            {filtered.map((c) => (
              <button
                key={c.id}
                onClick={() => navigate(`/project/${c.id}`)}
                className="flex w-full flex-col items-start border-b border-border py-4 text-left transition hover:bg-black/[0.02] dark:hover:bg-white/[0.03]"
              >
                <span className="text-[15px] font-semibold text-text">{c.name}</span>
                <span className="mt-0.5 text-[12px] text-faint">{timeAgo(c.lastActivityAt)}</span>
              </button>
            ))}
          </div>
        )}
      </div>
    </AppShell>
  )
}
