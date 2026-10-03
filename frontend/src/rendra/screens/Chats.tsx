import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { useProjects, useStartProject } from '../../api/queries'
import { EmptyState, ErrorState, Spinner } from '../../components/ui/States'
import AppShell from '../AppShell'
import { Ico, relTime } from '../parts'

const PAGE_SIZE = 10

export default function Chats() {
  const nav = useNavigate()
  const { selectedId } = useWorkspace()
  const { data: page, isLoading, isError, refetch } = useProjects(selectedId, { types: ['CHAT'] })
  const start = useStartProject()

  const [search, setSearch] = useState('')
  const [currentPage, setCurrentPage] = useState(0)

  const all = useMemo(() => page?.content ?? [], [page])

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return all
    return all.filter(
      (p) =>
        p.name.toLowerCase().includes(q) ||
        (p.initialPrompt ?? '').toLowerCase().includes(q),
    )
  }, [all, search])

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const safePage = Math.min(currentPage, totalPages - 1)
  const paginated = filtered.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE)

  const handleSearch = (v: string) => {
    setSearch(v)
    setCurrentPage(0)
  }

  const newChat = async () => {
    const project = await start.mutateAsync({ prompt: null, mode: 'CHAT', workspaceId: selectedId ?? undefined })
    nav(`/project/${project.id}`)
  }

  return (
    <AppShell active="chats">
      <div className="kap-page">
        <div className="kap-ph">
          <div>
            <h1>Chats</h1>
            <p>Your AI conversations, all in one place.</p>
          </div>
          <button type="button" className="kap-newbtn" disabled={start.isPending} onClick={() => void newChat()}>
            {start.isPending ? <Spinner size={14} /> : <Ico d="M12 5v14M5 12h14" size={15} sw={2.4} />} New chat
          </button>
        </div>

        <div className="kap-chats-search">
          <Ico d="M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z" size={15} />
          <input
            type="search"
            placeholder="Search chats…"
            value={search}
            onChange={(e) => handleSearch(e.target.value)}
            aria-label="Search chats"
          />
          {search && (
            <button type="button" className="kap-chats-clear" aria-label="Clear search" onClick={() => handleSearch('')}>
              <Ico d="M18 6L6 18M6 6l12 12" size={13} />
            </button>
          )}
        </div>

        {isLoading ? (
          <div style={{ display: 'grid', placeItems: 'center', padding: 60 }}><Spinner /></div>
        ) : isError ? (
          <ErrorState message="We couldn't load your chats." onRetry={() => void refetch()} />
        ) : filtered.length === 0 ? (
          <EmptyState
            title={search ? `No chats match "${search}"` : 'No chats yet'}
            hint={search ? 'Try a different search term.' : 'Start a new chat to get going.'}
          />
        ) : (
          <>
            <div className="kap-chats-list">
              {paginated.map((p) => (
                <button key={p.id} type="button" className="kap-chat-row" onClick={() => nav(`/project/${p.id}`)}>
                  <span className="kap-chat-icon">
                    <Ico d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z" size={16} />
                  </span>
                  <span className="kap-chat-body">
                    <b>{p.name}</b>
                    {p.initialPrompt && <small>{p.initialPrompt}</small>}
                  </span>
                  <span className="kap-chat-time">{relTime(p.lastActivityAt)}</span>
                  <Ico d="m9 18 6-6-6-6" size={14} />
                </button>
              ))}
            </div>

            {totalPages > 1 && (
              <div className="kap-chats-pager">
                <button
                  type="button"
                  className="kap-pager-btn"
                  disabled={safePage === 0}
                  onClick={() => setCurrentPage((p) => p - 1)}
                  aria-label="Previous page"
                >
                  <Ico d="m15 18-6-6 6-6" size={15} />
                </button>
                <span className="kap-pager-info">
                  {safePage + 1} / {totalPages}
                </span>
                <button
                  type="button"
                  className="kap-pager-btn"
                  disabled={safePage >= totalPages - 1}
                  onClick={() => setCurrentPage((p) => p + 1)}
                  aria-label="Next page"
                >
                  <Ico d="m9 18 6-6-6-6" size={15} />
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </AppShell>
  )
}
