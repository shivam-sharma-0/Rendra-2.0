import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useProjects, useStartProject } from '../../../api/queries'
import { NeuButton, NeuPager, NeuPanel, NeuSearchField } from '../../../components/neu'
import { EmptyState, ErrorState, Spinner } from '../../../components/ui/States'
import { useWorkspace } from '../../../workspace/WorkspaceContext'
import AppShell from '../../AppShell'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import PageHeader from '../../ui/PageHeader'
import ChatRow from './ChatRow'
import './chats.css'

const PAGE_SIZE = 10

/** The workspace's AI conversations: search, paging and "New chat". */
export default function Chats() {
  const nav = useNavigate()
  const { selectedId } = useWorkspace()
  const { data: page, isLoading, isError, refetch } = useProjects(selectedId, { types: ['CHAT'] })
  const start = useStartProject()
  const [search, setSearch] = useState('')
  const [pageIndex, setPageIndex] = useState(0)

  const matches = useMemo(() => {
    const all = page?.content ?? []
    const q = search.trim().toLowerCase()
    if (!q) return all
    return all.filter((p) => p.name.toLowerCase().includes(q) || (p.initialPrompt ?? '').toLowerCase().includes(q))
  }, [page, search])
  const pageCount = Math.max(1, Math.ceil(matches.length / PAGE_SIZE))
  const current = Math.min(pageIndex, pageCount - 1)
  const shown = matches.slice(current * PAGE_SIZE, (current + 1) * PAGE_SIZE)

  const updateSearch = (value: string) => { setSearch(value); setPageIndex(0) }
  const newChat = async () => {
    const project = await start.mutateAsync({ prompt: null, mode: 'CHAT', workspaceId: selectedId ?? undefined })
    nav(`/project/${project.id}`)
  }

  return (
    <AppShell active="chats" surface="plain">
      <div className="page">
        <div className="page__inner">
          <PageHeader
            title="Chats"
            description="Your AI conversations, all in one place."
            action={
              <NeuButton variant="primary" size="sm" pill disabled={start.isPending} onClick={() => void newChat()}>
                {start.isPending ? <Spinner size={14} /> : <Ico d={ICON.plus} size={14} sw={2.4} />}New chat
              </NeuButton>
            }
          />
          <NeuSearchField className="ch-search" value={search} onChange={updateSearch} label="Search chats" placeholder="Search chats…" />

          {isLoading ? (
            <div className="page__state"><Spinner /></div>
          ) : isError ? (
            <ErrorState message="We couldn't load your chats." onRetry={() => void refetch()} />
          ) : matches.length === 0 ? (
            <EmptyState
              title={search ? `No chats match "${search}"` : 'No chats yet'}
              hint={search ? 'Try a different search term.' : 'Start a new chat to get going.'}
            />
          ) : (
            <>
              <NeuPanel size="sm" className="ch-list" role="list">
                {shown.map((p) => <div role="listitem" key={p.id}><ChatRow chat={p} /></div>)}
              </NeuPanel>
              <NeuPager page={current} pageCount={pageCount} onChange={setPageIndex} />
            </>
          )}
        </div>
      </div>
    </AppShell>
  )
}
