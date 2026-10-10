import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useProjects, useStartProject } from '../../../api/queries'
import { useWorkspace } from '../../../workspace/WorkspaceContext'

/** The workspace's chats with search and client-side paging, plus "New chat" (the Chats screen and the sidebar's Chats dialog). */
export function useChatList(pageSize: number) {
  const nav = useNavigate()
  const { selectedId } = useWorkspace()
  const { data, isLoading, isError, refetch } = useProjects(selectedId, { types: ['CHAT'] })
  const start = useStartProject()
  const [search, setSearch] = useState('')
  const [pageIndex, setPageIndex] = useState(0)

  const matches = useMemo(() => {
    const all = data?.content ?? []
    const q = search.trim().toLowerCase()
    if (!q) return all
    return all.filter((p) => p.name.toLowerCase().includes(q) || (p.initialPrompt ?? '').toLowerCase().includes(q))
  }, [data, search])
  const pageCount = Math.max(1, Math.ceil(matches.length / pageSize))
  const page = Math.min(pageIndex, pageCount - 1)

  return {
    search,
    updateSearch: (value: string) => { setSearch(value); setPageIndex(0) },
    page,
    pageCount,
    setPage: setPageIndex,
    shown: matches.slice(page * pageSize, (page + 1) * pageSize),
    total: matches.length,
    isLoading,
    isError,
    refetch: () => void refetch(),
    starting: start.isPending,
    /** Starts an empty chat and opens it; `beforeOpen` runs first (e.g. to close a dialog). */
    newChat: async (beforeOpen?: () => void) => {
      const project = await start.mutateAsync({ prompt: null, mode: 'CHAT', workspaceId: selectedId ?? undefined })
      beforeOpen?.()
      nav(`/project/${project.id}`)
    },
  }
}
